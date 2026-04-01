package raf.rs.raft_gateway.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import raf.rs.raft_gateway.service.RaftGatewayService;

@RestController
@RequestMapping("/node")
public class NodeController {

    private final RaftGatewayService service;

    public NodeController(RaftGatewayService service) {
        this.service = service;
    }

    @PostMapping("/{nodeId}/pause")
    public ResponseEntity<String> pauseNode(@PathVariable int nodeId) {
        boolean success = service.pauseNode(nodeId);
        return success ? ResponseEntity.ok("ok") : ResponseEntity.internalServerError().body("failed");
    }

    @PostMapping("/{nodeId}/resume")
    public ResponseEntity<String> resumeNode(@PathVariable int nodeId) {
        boolean success = service.resumeNode(nodeId);
        return success ? ResponseEntity.ok("ok") : ResponseEntity.internalServerError().body("failed");
    }
}
