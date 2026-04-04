package raf.rs.raft_gateway.service;

import raf.rs.raft_gateway.dto.MessageRequest;

public interface RaftGatewayService {
    String addMessage(MessageRequest request);
    boolean pauseNode(int nodeId);
    boolean resumeNode(int nodeId);
}
