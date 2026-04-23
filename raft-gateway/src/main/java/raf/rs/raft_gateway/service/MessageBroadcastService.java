package raf.rs.raft_gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import raf.rs.AllMessages;
import raf.rs.Message;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class MessageBroadcastService {

    private static final Logger logger = LoggerFactory.getLogger(MessageBroadcastService.class);

    private final RaftGatewayService raftService;
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final Map<Integer, Boolean> lastDeletedByIndex = new HashMap<>();
    private int lastBroadcastIndex = 0;

    public MessageBroadcastService(RaftGatewayService raftService) {
        this.raftService = raftService;
    }

    public SseEmitter subscribe() {
        // No timeout: SSE connections stay open until client disconnects.
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        return emitter;
    }

    @Scheduled(fixedDelay = 500)
    public void pollAndBroadcast() {
        if (subscribers.isEmpty()) return;
        AllMessages all;
        try {
            all = raftService.getAllMessages();
        } catch (Exception e) {
            return;
        }

        for (Message m : all.getMessageList()) {
            int idx = m.getLogIndex();
            boolean isNew = idx > lastBroadcastIndex;
            Boolean prevDeleted = lastDeletedByIndex.get(idx);
            boolean deletionChanged = prevDeleted != null && prevDeleted != m.getDeleted();

            if (isNew || deletionChanged) {
                broadcast(toPayload(m));
            }
            lastDeletedByIndex.put(idx, m.getDeleted());
            if (idx > lastBroadcastIndex) lastBroadcastIndex = idx;
        }
    }

    private void broadcast(Map<String, Object> payload) {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("message").data(payload));
            } catch (IOException e) {
                emitter.complete();
                subscribers.remove(emitter);
            }
        }
    }

    private Map<String, Object> toPayload(Message m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", String.valueOf(m.getMessageId()));
        map.put("username", m.getUser());
        map.put("content", m.getMessage());
        map.put("timestamp", m.getTimestamp());
        map.put("deleted", m.getDeleted());
        map.put("logIndex", m.getLogIndex());
        return map;
    }
}
