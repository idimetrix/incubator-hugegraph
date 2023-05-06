package org.apache.hugegraph.pd.meta;

import com.baidu.hugegraph.pd.common.PDException;
import com.baidu.hugegraph.pd.common.PartitionCache;

import org.apache.hugegraph.pd.config.PDConfig;

import com.baidu.hugegraph.pd.grpc.Metapb;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 分区信息管理
 */
@Slf4j
public class PartitionMeta extends MetadataRocksDBStore {
    private PDConfig pdConfig;
    private PartitionCache cache;

    static String CID_GRAPH_ID_KEY = "GraphID";
    static int    CID_GRAPH_ID_MAX = 0xFFFE;

    public PartitionMeta(PDConfig pdConfig) {
        super(pdConfig);
        this.pdConfig = pdConfig;
        //this.timeout = pdConfig.getEtcd().getTimeout();
        this.cache = new PartitionCache();
    }
    /**
     * 初始化，加载所有的分区
     */
    public void init() throws PDException {
        loadShardGroups();
        loadGraphs();
    }

    public void reload() throws PDException {
        cache.clear();
        loadShardGroups();
        loadGraphs();
    }

    private void loadGraphs() throws PDException {
        byte[] key = MetadataKeyHelper.getGraphPrefix();
        List<Metapb.Graph> graphs = scanPrefix(Metapb.Graph.parser(), key);
        for (Metapb.Graph graph : graphs) {
            cache.updateGraph(graph);
            loadPartitions(graph);
        }
    }

    /**
     * partition 和 shard group分开存储，再init的时候，需要加载进来
     * @throws PDException
     */
    private void loadShardGroups() throws PDException {
        byte[] shardGroupPrefix = MetadataKeyHelper.getShardGroupPrefix();
        for (var shardGroup : scanPrefix(Metapb.ShardGroup.parser(), shardGroupPrefix)){
            cache.updateShardGroup(shardGroup);
        }
    }

    private void loadPartitions(Metapb.Graph graph) throws PDException{
        byte[] prefix = MetadataKeyHelper.getPartitionPrefix( graph.getGraphName());
        List<Metapb.Partition> partitions = scanPrefix(Metapb.Partition.parser(), prefix);
        partitions.forEach(p->{
            cache.updatePartition(p);
        });
    }

    /**
     * 根据id查找分区 (先从缓存找，再到数据库中找）
     * @param graphName
     * @param partId
     * @return
     * @throws PDException
     */
    public Metapb.Partition getPartitionById(String graphName, int partId) throws PDException {
        var pair =  cache.getPartitionById(graphName, partId);
        Metapb.Partition partition;
        if (pair == null) {
            byte[] key = MetadataKeyHelper.getPartitionKey( graphName, partId);
            partition = getOne(Metapb.Partition.parser(), key);
            if ( partition != null ) {
                cache.updatePartition(partition);
            }
        }else{
            partition = pair.getKey();
        }
        return partition;
    }
    public List<Metapb.Partition> getPartitionById(int partId) throws PDException {
        List<Metapb.Partition> partitions = new ArrayList<>();
        cache.getGraphs().forEach(graph -> {
            cache.getPartitions(graph.getGraphName()).forEach(partition -> {
                if ( partition.getId() == partId )
                    partitions.add(partition);
            });
        });
       return partitions;
    }

    /**
     * 根据code查找分区

     */
    public Metapb.Partition getPartitionByCode(String graphName, long code) throws PDException {
        var pair = cache.getPartitionByCode(graphName, code);
        if (pair != null){
            return pair.getKey();
        }
        return null;
    }

    public Metapb.Graph getAndCreateGraph(String graphName) throws PDException {
        return getAndCreateGraph(graphName, pdConfig.getPartition().getTotalCount());
    }

    public Metapb.Graph getAndCreateGraph(String graphName, int partitionCount) throws PDException {

        if (partitionCount > pdConfig.getPartition().getTotalCount()) {
            partitionCount = pdConfig.getPartition().getTotalCount();
        }

        // 管理图，只有一个分区
        if (graphName.endsWith("/s") || graphName.endsWith("/m")){
            partitionCount = 1;
        }

        Metapb.Graph graph = cache.getGraph(graphName);
        if ( graph == null ){
            // 保存图信息
            graph = Metapb.Graph.newBuilder()
                    .setGraphName(graphName)
                    .setPartitionCount(partitionCount)
                    .setState(Metapb.PartitionState.PState_Normal)
                    .build();
            updateGraph(graph);
        }
        return graph;
    }

    /**
     * 保存分区信息
     * @param partition
     * @return
     * @throws PDException
     */
    public Metapb.Partition updatePartition(Metapb.Partition partition) throws PDException {
        if ( !cache.hasGraph(partition.getGraphName())){
            getAndCreateGraph(partition.getGraphName());
        }
        byte[] key = MetadataKeyHelper.getPartitionKey( partition.getGraphName(), partition.getId());
        put(key, partition.toByteString().toByteArray());
        cache.updatePartition(partition);
        return partition;
    }

    /**
     * 检查数据库，是否存在对应的图，不存在，则创建。
     * 更新partition的 version, conf version 和 shard list
     * @param partition
     * @return
     * @throws PDException
     */
    public Metapb.Partition updateShardList(Metapb.Partition partition) throws PDException {
        if ( !cache.hasGraph(partition.getGraphName())){
            getAndCreateGraph(partition.getGraphName());
        }

        Metapb.Partition pt = getPartitionById(partition.getGraphName(), partition.getId());
        //  pt = pt.toBuilder().setVersion(partition.getVersion())
        //        .setConfVer(partition.getConfVer())
        //        .clearShards()
        //        .addAllShards(partition.getShardsList()).build();

        byte[] key = MetadataKeyHelper.getPartitionKey( pt.getGraphName(), pt.getId());
        put(key, pt.toByteString().toByteArray());
        cache.updatePartition(pt);
        return partition;
    }
    /**
     * 删除所有分区
     */
    public long removeAllPartitions(String graphName) throws PDException {
        cache.removeAll(graphName);
        byte[] prefix = MetadataKeyHelper.getPartitionPrefix( graphName);
        return removeByPrefix(prefix);
    }

    public long removePartition(String graphName, int id) throws PDException {
        cache.remove(graphName, id);
        byte[] key = MetadataKeyHelper.getPartitionKey( graphName, id);
        return remove(key);
    }

    public void updatePartitionStats(Metapb.PartitionStats stats) throws PDException {
        for(String graphName : stats.getGraphNameList()) {
            byte[] prefix = MetadataKeyHelper.getPartitionStatusKey(graphName, stats.getId());
            put(prefix, stats.toByteArray());
        }
    }

    /**
     * 获取分区状态
     */
    public Metapb.PartitionStats getPartitionStats(String graphName, int id) throws PDException {
        byte[] prefix = MetadataKeyHelper.getPartitionStatusKey(graphName, id);
        return getOne(Metapb.PartitionStats.parser(),prefix);
    }


    /**
     * 获取分区状态
     */
    public List<Metapb.PartitionStats> getPartitionStats(String graphName) throws PDException {
        byte[] prefix = MetadataKeyHelper.getPartitionStatusPrefixKey(graphName);
        return scanPrefix(Metapb.PartitionStats.parser(),prefix);
    }

    /**
     * 更新图信息
     * @param graph
     * @return
     */
    public Metapb.Graph updateGraph(Metapb.Graph graph) throws PDException {
        log.info("updateGraph {}", graph);
        byte[] key = MetadataKeyHelper.getGraphKey( graph.getGraphName());
        // 保存图信息
        put(key, graph.toByteString().toByteArray());
        cache.updateGraph(graph);
        return graph;
    }

    public List<Metapb.Partition> getPartitions(){
        List<Metapb.Partition> partitions = new ArrayList<>();
        List<Metapb.Graph> graphs = cache.getGraphs();
        graphs.forEach(e->{
            partitions.addAll(cache.getPartitions(e.getGraphName()));
        });
        return partitions;
    }

    public List<Metapb.Partition> getPartitions(String graphName){
        return cache.getPartitions(graphName);
    }

    public List<Metapb.Graph> getGraphs() throws PDException {
        byte[] key = MetadataKeyHelper.getGraphPrefix();
        return scanPrefix(Metapb.Graph.parser(), key);
    }

    public Metapb.Graph getGraph(String graphName) throws PDException {
        byte[] key = MetadataKeyHelper.getGraphKey( graphName);
        return getOne(Metapb.Graph.parser(), key);
    }

    /**
     * 删除图，并删除图id
     */
    public long removeGraph(String graphName) throws PDException {
        byte[] key = MetadataKeyHelper.getGraphKey( graphName);
        long l = remove(key);
        return l;
    }

    public PartitionCache getPartitionCache(){
        return cache;
    }
}
