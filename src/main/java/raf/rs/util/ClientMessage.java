package raf.rs.util;

import java.time.LocalDateTime;

public class ClientMessage {

    private int messageId;
    private String sender;
    private String text;
    private int stickerId;
    private LocalDateTime timeSent;
    private boolean deleted;

    public ClientMessage(int messageId, String sender, String text, int stickerId, LocalDateTime timeSent) {
        this.messageId = messageId;
        this.sender = sender;
        this.text = text;
        this.stickerId = stickerId;
        this.timeSent = timeSent;
    }


}
