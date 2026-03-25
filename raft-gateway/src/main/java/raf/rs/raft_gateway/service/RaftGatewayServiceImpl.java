package raf.rs.raft_gateway.service;

import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.stereotype.Service;
import raf.rs.AddCommand;
import raf.rs.ClientMessageRes;
import raf.rs.Command;
import raf.rs.RAFTGrpc;
import raf.rs.raft_gateway.dto.MessageRequest;

@Service
public class RaftGatewayServiceImpl implements RaftGatewayService {

    @Override
    public boolean addMessage(MessageRequest request) {
        System.out.println("Message received");
        Channel channel = ManagedChannelBuilder.forAddress("localhost", 9002).usePlaintext().build();
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
        System.out.println("client received");
        return response.getSuccess();
    }
}
