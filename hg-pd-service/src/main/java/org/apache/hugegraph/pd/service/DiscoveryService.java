package org.apache.hugegraph.pd.service;

import com.baidu.hugegraph.pd.RegistryService;
import com.baidu.hugegraph.pd.common.PDException;
import com.baidu.hugegraph.pd.common.PDRuntimeException;
import com.baidu.hugegraph.pd.config.PDConfig;
import com.baidu.hugegraph.pd.grpc.Pdpb;
import com.baidu.hugegraph.pd.grpc.discovery.DiscoveryServiceGrpc;
import com.baidu.hugegraph.pd.grpc.discovery.NodeInfo;
import com.baidu.hugegraph.pd.grpc.discovery.NodeInfos;
import com.baidu.hugegraph.pd.grpc.discovery.Query;
import com.baidu.hugegraph.pd.grpc.discovery.RegisterInfo;
import org.apache.hugegraph.pd.license.LicenseVerifierService;
import org.apache.hugegraph.pd.pulse.PDPulseSubject;
import com.baidu.hugegraph.pd.raft.RaftEngine;
import com.baidu.hugegraph.pd.raft.RaftStateListener;
import org.apache.hugegraph.pd.watch.PDWatchSubject;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.stub.AbstractBlockingStub;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author zhangyingjie
 * @date 2021/12/20
 **/
@Slf4j
@GRpcService
public class DiscoveryService extends DiscoveryServiceGrpc.DiscoveryServiceImplBase implements RaftStateListener {

    @Autowired
    private PDConfig pdConfig;
    static final AtomicLong id = new AtomicLong();
    RegistryService register = null;
    LicenseVerifierService licenseVerifierService;

    @PostConstruct
    public void init() throws PDException {
        log.info("PDService init………… {}", pdConfig);
        RaftEngine.getInstance().init(pdConfig.getRaft());
        RaftEngine.getInstance().addStateListener(this);
        register = new RegistryService(pdConfig);
        licenseVerifierService = new LicenseVerifierService(pdConfig);
    }

    private Pdpb.ResponseHeader newErrorHeader(PDException e) {
        Pdpb.ResponseHeader header = Pdpb.ResponseHeader.newBuilder().setError(
                Pdpb.Error.newBuilder().setTypeValue(e.getErrorCode()).setMessage(e.getMessage())).build();
        return header;
    }

    private static final String CORES = "cores";

    @Override
    public void register(NodeInfo request, io.grpc.stub.StreamObserver<RegisterInfo> observer) {
        if (!isLeader()) {
            redirectToLeader(DiscoveryServiceGrpc.getRegisterMethod(), request, observer);
            return;
        }
        int outTimes = pdConfig.getDiscovery().getHeartbeatOutTimes();
        RegisterInfo registerInfo;
        try {
            if (request.getAppName().equals("hg")) {
                Query queryRequest = Query.newBuilder().setAppName(request.getAppName())
                                          .setVersion(request.getVersion()).build();
                NodeInfos nodes = register.getNodes(queryRequest);
                String address = request.getAddress();
                int nodeCount = nodes.getInfoCount() + 1;
                for (NodeInfo node : nodes.getInfoList()) {
                    if (node.getAddress().equals(address)) {
                        nodeCount = nodes.getInfoCount();
                        break;
                    }
                }
                Map<String, String> labelsMap = request.getLabelsMap();
                String coreCount = labelsMap.get(CORES);
                if (StringUtils.isEmpty(coreCount)) {
                    throw new PDException(-1, "core count can not be null");
                }
                int core = Integer.parseInt(coreCount);
                licenseVerifierService.verify(core, nodeCount);
            }
            register.register(request, outTimes);
            String valueId = request.getId();
            registerInfo = RegisterInfo.newBuilder().setNodeInfo(NodeInfo.newBuilder().setId(
                    "0".equals(valueId) ? String.valueOf(id.incrementAndGet()) : valueId).build()).build();

        } catch (PDException e) {
            registerInfo = RegisterInfo.newBuilder().setHeader(newErrorHeader(e)).build();
            log.debug("registerStore exception: ", e);
        } catch (PDRuntimeException ex) {
            Pdpb.Error error = Pdpb.Error.newBuilder().setTypeValue(ex.getErrorCode())
                                         .setMessage(ex.getMessage()).build();
            Pdpb.ResponseHeader header = Pdpb.ResponseHeader.newBuilder().setError(error).build();
            registerInfo = RegisterInfo.newBuilder().setHeader(header).build();
            log.debug("registerStore exception: ", ex);
        } catch (Exception e) {
            Pdpb.Error error = Pdpb.Error.newBuilder().setTypeValue(Pdpb.ErrorType.UNKNOWN.getNumber())
                                         .setMessage(e.getMessage()).build();
            Pdpb.ResponseHeader header = Pdpb.ResponseHeader.newBuilder().setError(error).build();
            registerInfo = RegisterInfo.newBuilder().setHeader(header).build();
        }
        observer.onNext(registerInfo);
        observer.onCompleted();
    }

    public void getNodes(Query request, io.grpc.stub.StreamObserver<NodeInfos> responseObserver) {
        if (!isLeader()) {
            redirectToLeader(DiscoveryServiceGrpc.getGetNodesMethod(), request, responseObserver);
            return;
        }
        responseObserver.onNext(register.getNodes(request));
        responseObserver.onCompleted();
    }

    private ManagedChannel channel;

    public boolean isLeader() {
        return RaftEngine.getInstance().isLeader();
    }

    private <ReqT, RespT, StubT extends AbstractBlockingStub<StubT>> void redirectToLeader(
            MethodDescriptor<ReqT, RespT> method, ReqT req, io.grpc.stub.StreamObserver<RespT> observer) {
        try {
            if (channel == null) {
                synchronized (this) {
                    if (channel == null) {
                        channel = ManagedChannelBuilder
                                .forTarget(RaftEngine.getInstance().getLeaderGrpcAddress()).usePlaintext()
                                .build();
                    }
                }
                log.info("Grpc get leader address {}", RaftEngine.getInstance().getLeaderGrpcAddress());
            }

            io.grpc.stub.ClientCalls.asyncUnaryCall(channel.newCall(method, CallOptions.DEFAULT), req,
                                                    observer);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public synchronized void onRaftLeaderChanged() {
        channel = null;
        if (!isLeader()) {
            try {
                String message = "lose leader";
                PDPulseSubject.notifyError(message);
                PDWatchSubject.notifyError(message);
            } catch (Exception e) {
                log.error("notifyError error {}", e);
            }
        }
    }
}
