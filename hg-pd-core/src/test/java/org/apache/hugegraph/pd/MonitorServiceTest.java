package org.apache.hugegraph.pd;

import java.util.concurrent.ExecutionException;

import org.apache.hugegraph.pd.config.PDConfig;
import org.junit.Assert;
import org.junit.BeforeClass;

import com.baidu.hugegraph.pd.common.PDException;
import com.baidu.hugegraph.pd.grpc.Metapb;

// import org.junit.Test;

public class MonitorServiceTest {
    static PDConfig pdConfig;

    @BeforeClass
    public static void init() throws ExecutionException, InterruptedException {
        pdConfig = new PDConfig() {{
            this.setClusterId(100);
            this.setPatrolInterval(1);
        }};

        //pdConfig.setEtcd(new PDConfig().new Etcd() {{
        //    this.setAddress("http://localhost:2379");
        //
        //}});
        pdConfig.setStore(new PDConfig().new Store() {{
            this.setMaxDownTime(1);
            this.setKeepAliveTimeout(5);
        }});

        pdConfig.setPartition(new PDConfig().new Partition() {{
            this.setShardCount(3);
            this.setTotalCount(10);
        }});

        clearClusterData();
    }

    public static void clearClusterData() throws ExecutionException, InterruptedException {
        //Client client = Client.builder().endpoints(pdConfig.getEtcd().getAddress()).build();
        //KV kvClient = client.getKVClient();
        //
        //ByteSequence key = ByteSequence.from("HUGEGRAPH/" + pdConfig.getClusterId(), Charset.forName("utf-8"));
        //CompletableFuture<DeleteResponse> rsp = kvClient.delete(key, DeleteOption.newBuilder().isPrefix(true).build());
        //System.out.println("删除数量 : " + rsp.get().getDeleted());
        //kvClient.close();
        //client.close();
    }

    // @Test
    public void testPatrolStores() throws PDException, InterruptedException {
        StoreNodeService storeService = new StoreNodeService(pdConfig);
        PartitionService partitionService = new PartitionService(pdConfig, storeService);
        TaskScheduleService monitorService = new TaskScheduleService(pdConfig, storeService, partitionService);
        storeService.init(partitionService);
        partitionService.init();
        monitorService.init();

        int count = 6;
        Metapb.Store[] stores = new Metapb.Store[count];
        for (int i = 0; i < count; i++) {
            Metapb.Store store = Metapb.Store.newBuilder()
                    .setId(0)
                    .setAddress("" + i)
                    .setDeployPath("/data")
                    .addLabels(Metapb.StoreLabel.newBuilder()
                            .setKey("namespace")
                            .setValue("default").build())
                    .build();
            stores[i] = storeService.register(store);
            System.out.println("新注册store， id = " + Long.toHexString(stores[i].getId()));
        }
        Metapb.Graph graph = Metapb.Graph.newBuilder()
                .setGraphName("defaultGH")

                .setPartitionCount(10)
                .build();
        partitionService.updateGraph(graph);
        Thread.sleep(10000);
        count = 0;
        count += storeService.getStores("").stream().filter(store -> store.getState() == Metapb.StoreState.Tombstone).count();

        Assert.assertEquals(6, count);

    }


}
