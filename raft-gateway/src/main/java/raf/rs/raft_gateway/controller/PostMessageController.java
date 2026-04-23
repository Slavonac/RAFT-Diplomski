package raf.rs.raft_gateway.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import raf.rs.AllMessages;
import raf.rs.raft_gateway.dto.MessageRequest;
import raf.rs.raft_gateway.service.RaftGatewayService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class PostMessageController {

    private final RaftGatewayService service;

    public PostMessageController(RaftGatewayService service) {
        this.service = service;
    }

    @PostMapping("/message")
    public ResponseEntity<?> addMessage(@RequestBody MessageRequest request) {
        String result = service.addMessage(request);
        if (result.isEmpty()) {
            return ResponseEntity.ok(Map.of("id", request.getId().hashCode()));
        }
        return ResponseEntity.internalServerError().body(Map.of("error", result));
    }

    @GetMapping("/message")
    public ResponseEntity<List<Map<String, Object>>> getAllMessages() {
        AllMessages allMessages = service.getAllMessages();
        List<Map<String, Object>> response = allMessages.getMessageList().stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", String.valueOf(m.getMessageId()));
                    map.put("username", m.getUser());
                    map.put("content", m.getMessage());
                    map.put("timestamp", m.getTimestamp());
                    map.put("deleted", m.getDeleted());
                    map.put("logIndex", m.getLogIndex());
                    return map;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/node/leader")
    public ResponseEntity<?> getLeader() {
        int leaderIndex = service.getLeaderIndex();
        if (leaderIndex == -1) {
            return ResponseEntity.ok(Map.of("leaderId", -1));
        }
        return ResponseEntity.ok(Map.of("leaderId", leaderIndex));
    }

    @DeleteMapping("/message/{id}")
    public ResponseEntity<?> deleteMessage(@PathVariable("id") String id) {
        String result = service.deleteMessage(id);
        if (result.isEmpty()) {
            return ResponseEntity.ok(Map.of("id", id));
        }
        return ResponseEntity.internalServerError().body(Map.of("error", result));
    }
}
