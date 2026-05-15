package raf.rs.raft_gateway.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import raf.rs.LogEntryInfo;
import raf.rs.NodeLogRes;
import raf.rs.raft_gateway.service.RaftGatewayService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    @GetMapping("/{nodeId}/log")
    public ResponseEntity<?> getNodeLog(@PathVariable int nodeId) {
        try {
            NodeLogRes res = service.getNodeLog(nodeId);
            List<Map<String, Object>> entries = res.getEntriesList().stream()
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("index", e.getIndex());
                        m.put("term", e.getTerm());
                        m.put("commandType", e.getCommandType());
                        m.put("user", e.getUser());
                        m.put("content", e.getContent());
                        m.put("committed", e.getCommitted());
                        return m;
                    })
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of(
                    "nodeState", res.getNodeState(),
                    "commitIndex", res.getCommitIndex(),
                    "entries", entries
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
