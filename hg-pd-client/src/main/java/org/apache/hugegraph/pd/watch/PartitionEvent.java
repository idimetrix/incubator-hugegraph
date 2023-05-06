package org.apache.hugegraph.pd.watch;

import com.baidu.hugegraph.pd.grpc.watch.WatchChangeType;

import java.util.Objects;

/**
 * @author lynn.bond@hotmail.com created on 2021/11/4
 */
public class PartitionEvent {
    private String graph;
    private int partitionId;
    private ChangeType changeType;

    public PartitionEvent(String graph, int partitionId, ChangeType changeType) {
        this.graph = graph;
        this.partitionId = partitionId;
        this.changeType = changeType;
    }

    public String getGraph() {
        return this.graph;
    }

    public int getPartitionId() {
        return this.partitionId;
    }

    public ChangeType getChangeType() {
        return this.changeType;
    }

    public enum ChangeType {
        UNKNOWN,
        ADD,
        ALTER,
        DEL;

        public static ChangeType grpcTypeOf(WatchChangeType grpcType) {
            switch (grpcType) {
                case WATCH_CHANGE_TYPE_ADD:
                    return ADD;
                case WATCH_CHANGE_TYPE_ALTER:
                    return ALTER;
                case WATCH_CHANGE_TYPE_DEL:
                    return DEL;
                default:
                    return UNKNOWN;
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PartitionEvent that = (PartitionEvent) o;
        return partitionId == that.partitionId && Objects.equals(graph, that.graph) && changeType == that.changeType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(graph, partitionId, changeType);
    }

    @Override
    public String toString() {
        return "PartitionEvent{" +
                "graph='" + graph + '\'' +
                ", partitionId=" + partitionId +
                ", changeType=" + changeType +
                '}';
    }
}
