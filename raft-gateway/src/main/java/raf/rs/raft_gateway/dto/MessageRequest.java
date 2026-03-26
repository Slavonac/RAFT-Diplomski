package raf.rs.raft_gateway.dto;

public class MessageRequest {
    private String id;
    private String timestamp;
    private String username;
    private String content;
    private String status;

    public String getId() { return id; }
    public String getTimestamp() { return timestamp; }
    public String getUsername() { return username; }
    public String getContent() { return content; }
}
