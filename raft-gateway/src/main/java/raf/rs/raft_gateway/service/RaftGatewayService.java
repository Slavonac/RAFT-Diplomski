package raf.rs.raft_gateway.service;

import raf.rs.raft_gateway.dto.MessageRequest;

public interface RaftGatewayService {
    boolean addMessage(MessageRequest request);
}
