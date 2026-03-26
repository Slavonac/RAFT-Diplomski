package raf.rs.raft_gateway.service;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import raf.rs.AddCommand;
import raf.rs.ClientMessageRes;
import raf.rs.Command;
import raf.rs.RAFTGrpc;
import raf.rs.raft_gateway.dto.MessageRequest;

@Service
public class RaftGatewayServiceImpl implements RaftGatewayService {

    private final String raftClusterHost;
    private RAFTGrpc.RAFTBlockingStub stub;
    private int raftClusterPort;

    public RaftGatewayServiceImpl(
            @Value("${raft.cluster.host}") String raftClusterHost,
            @Value("${raft.cluster.port}") int raftClusterPort) {
        this.raftClusterHost = raftClusterHost;
        this.raftClusterPort = raftClusterPort;
        ManagedChannel channel = ManagedChannelBuilder.forAddress(raftClusterHost, raftClusterPort)
                .usePlaintext()
                .build();
        stub = RAFTGrpc.newBlockingStub(channel);
    }

    @Override
    public boolean addMessage(MessageRequest request) {
        System.out.println("Message received");
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
            channel = ManagedChannelBuilder.forAddress(raftClusterHost, Integer.parseInt(response.getInfo())).usePlaintext().build();
            stub = RAFTGrpc.newBlockingStub(channel);
            response = stub.clientCommand(command);
        }

        return response.getSuccess();
    }
}
