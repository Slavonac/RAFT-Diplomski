package raf.rs.raft_gateway.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import raf.rs.raft_gateway.service.MessageBroadcastService;

@RestController
public class SseController {

    private final MessageBroadcastService broadcastService;

    public SseController(MessageBroadcastService broadcastService) {
        this.broadcastService = broadcastService;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcastService.subscribe();
    }
}
