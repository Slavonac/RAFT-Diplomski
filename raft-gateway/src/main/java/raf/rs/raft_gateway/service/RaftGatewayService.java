package raf.rs.raft_gateway.service;

import raf.rs.AllMessages;
import raf.rs.raft_gateway.dto.MessageRequest;

public interface RaftGatewayService {
    String addMessage(MessageRequest request);
    String deleteMessage(String messageId);
    boolean clearMessages();
    boolean pauseNode(int nodeId);
    boolean resumeNode(int nodeId);
    AllMessages getAllMessages();
    int getLeaderIndex();
}
