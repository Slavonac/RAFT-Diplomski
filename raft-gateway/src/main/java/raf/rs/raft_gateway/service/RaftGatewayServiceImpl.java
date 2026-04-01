package raf.rs.raft_gateway.service;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import raf.rs.AddCommand;
import raf.rs.ClientMessageRes;
import raf.rs.Command;
import raf.rs.PauseReq;
import raf.rs.ResumeReq;
import raf.rs.RAFTGrpc;
import raf.rs.raft_gateway.dto.MessageRequest;

@Service
public class RaftGatewayServiceImpl implements RaftGatewayService {

    private String raftClusterHost;
    private RAFTGrpc.RAFTBlockingStub stub;
    private int raftClusterPort;
    private String[] nodeAddresses;

    public RaftGatewayServiceImpl(
            @Value("${raft.cluster.host}") String raftClusterHost,
            @Value("${raft.cluster.port}") int raftClusterPort,
            @Value("${raft.cluster.nodes}") String raftClusterNodes) {
        this.raftClusterHost = raftClusterHost;
        this.raftClusterPort = raftClusterPort;
        this.nodeAddresses = raftClusterNodes.split(",");
        ManagedChannel channel = ManagedChannelBuilder.forAddress(raftClusterHost, raftClusterPort)
                .usePlaintext()
                .build();
        stub = RAFTGrpc.newBlockingStub(channel);
    }

    @Override
    public boolean addMessage(MessageRequest request) {

        Channel channel = ManagedChannelBuilder.forAddress(raftClusterHost, raftClusterPort).usePlaintext().build();

        RAFTGrpc.RAFTBlockingStub stub = RAFTGrpc.newBlockingStub(channel);
        AddCommand addCommand = AddCommand.newBuilder()
                .setMessageId(request.getId().hashCode())
                .setUser(request.getUsername())
                .setMessage(request.getContent())
                .setTimestamp(request.getTimestamp())
                .build();

        Command command = Command.newBuilder()
                .setAddCommand(addCommand)
                .build();

        ClientMessageRes response = stub.clientCommand(command);

        if (!response.getSuccess()) {
            raftClusterHost = response.getInfo().split(":")[0];
            raftClusterPort = Integer.parseInt(response.getInfo().split(":")[1]);

            channel = ManagedChannelBuilder.forAddress(raftClusterHost, raftClusterPort).usePlaintext().build();
            stub = RAFTGrpc.newBlockingStub(channel);
            response = stub.clientCommand(command);
        }

        return response.getSuccess();
    }

    @Override
    public boolean pauseNode(int nodeId) {
        try {
            String[] parts = nodeAddresses[nodeId].trim().split(":");
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress(parts[0], Integer.parseInt(parts[1]))
                    .usePlaintext()
                    .build();
            RAFTGrpc.newBlockingStub(channel).pause(PauseReq.newBuilder().build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean resumeNode(int nodeId) {
        try {
            String[] parts = nodeAddresses[nodeId].trim().split(":");
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress(parts[0], Integer.parseInt(parts[1]))
                    .usePlaintext()
                    .build();
            RAFTGrpc.newBlockingStub(channel).resume(ResumeReq.newBuilder().build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
