package raf.rs.raft_gateway.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import raf.rs.raft_gateway.dto.MessageRequest;
import raf.rs.raft_gateway.service.RaftGatewayService;

@RestController
public class PostMessageController {

    private final RaftGatewayService service;

    public PostMessageController(RaftGatewayService service) {
        this.service = service;
    }

    @PostMapping("/message")
    public ResponseEntity<String> addMessage(@RequestBody MessageRequest request) {
        String message = service.addMessage(request);
        return message.isEmpty() ? ResponseEntity.ok("ok") : ResponseEntity.internalServerError().body(message);
    }
}
