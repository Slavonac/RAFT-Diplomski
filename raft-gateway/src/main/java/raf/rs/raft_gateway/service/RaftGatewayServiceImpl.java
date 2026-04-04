package raf.rs.raft_gateway.service;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import raf.rs.AddCommand;
import raf.rs.ClientMessageRes;
import raf.rs.Command;
import raf.rs.PauseReq;
import raf.rs.ResumeReq;
import raf.rs.RAFTGrpc;
import raf.rs.raft_gateway.dto.MessageRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class RaftGatewayServiceImpl implements RaftGatewayService {

    private String raftClusterHost;
    private int raftClusterPort;
    private List<String> nodeAddresses;
    private List<String> deadNodes;
    private final Map<String, ManagedChannel> channelMap;
    private final Map<String, RAFTGrpc.RAFTBlockingStub> stubMap;

    public RaftGatewayServiceImpl(
            @Value("${raft.cluster.host}") String raftClusterHost,
            @Value("${raft.cluster.port}") int raftClusterPort,
            @Value("${raft.cluster.nodes}") String raftClusterNodes) {
        this.raftClusterHost = raftClusterHost;
        this.raftClusterPort = raftClusterPort;
        this.deadNodes = new ArrayList<>();
        this.nodeAddresses = List.of(raftClusterNodes.split(","));
        this.channelMap = new HashMap<>();
        this.stubMap = new HashMap<>();
        for (String address : nodeAddresses) {
            String[] parts = address.trim().split(":");
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress(parts[0], Integer.parseInt(parts[1]))
                    .usePlaintext()
                    .build();
            channelMap.put(address.trim(), channel);
            stubMap.put(address.trim(), RAFTGrpc.newBlockingStub(channel));
        }
    }

    private RAFTGrpc.RAFTBlockingStub stubFor(String address) {
        return stubMap.get(address.trim());
    }

    @Override
    public String addMessage(MessageRequest request) {
        String currentAddress = raftClusterHost + ":" + raftClusterPort;

        AddCommand addCommand = AddCommand.newBuilder()
                .setMessageId(request.getId().hashCode())
                .setUser(request.getUsername())
                .setMessage(request.getContent())
                .setTimestamp(request.getTimestamp())
                .build();

        Command command = Command.newBuilder()
                .setAddCommand(addCommand)
                .build();

        ClientMessageRes response = null;
        try {
            response = stubFor(currentAddress).clientCommand(command);
        } catch (io.grpc.StatusRuntimeException e) {
            if (e.getStatus().getCode().equals(Status.Code.UNAVAILABLE)) {
                List<String> candidates = new ArrayList<>(nodeAddresses);
                System.out.println("Candidates: " + candidates);
                System.out.println("Dead nodes " + deadNodes);
                candidates.removeAll(deadNodes);
                if (candidates.isEmpty()) {
                    System.out.println("All nodes are dead zzz");
                    return "No nodes available";
                }
                currentAddress = candidates.get(new Random().nextInt(candidates.size())).trim();
                raftClusterHost = currentAddress.split(":")[0];
                raftClusterPort = Integer.parseInt(currentAddress.split(":")[1]);
                response = stubFor(currentAddress).clientCommand(command);
            }
        }

        if (response == null)
            return "Error with sending a message";

        if (!response.getSuccess()) {
            if (response.getInfo().equals("TO"))
                return "Server error";
            String leaderAddress = response.getInfo().trim();
            if (!leaderAddress.contains(":"))
                return "Leader unknown";
            raftClusterHost = leaderAddress.split(":")[0];
            raftClusterPort = Integer.parseInt(leaderAddress.split(":")[1]);
            response = stubFor(leaderAddress).clientCommand(command);
        }

        return "";
    }

    @Override
    public boolean pauseNode(int nodeId) {
        try {
            String address = nodeAddresses.get(nodeId).trim();
            deadNodes.add(address);
            stubFor(address).pause(PauseReq.newBuilder().build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean resumeNode(int nodeId) {
        try {
            String address = nodeAddresses.get(nodeId).trim();
            deadNodes.remove(address);
            stubFor(address).resume(ResumeReq.newBuilder().build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
