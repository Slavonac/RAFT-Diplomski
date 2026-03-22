package raf.rs.util;

public class StateMessage {

    private String timestamp;
    private String user;
    private String message;
    private long messageId;
    private boolean deleted;

    public StateMessage(String timestamp, String user, String message, long messageId, boolean deleted) {
        this.timestamp = timestamp;
        this.user = user;
        this.message = message;
        this.messageId = messageId;
        this.deleted = deleted;
    }
    public StateMessage() {

    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getMessageId() {
        return messageId;
    }

    public void setMessageId(long messageId) {
        this.messageId = messageId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
