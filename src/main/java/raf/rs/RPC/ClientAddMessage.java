package raf.rs.RPC;

import java.time.LocalDateTime;

public class ClientAddMessage extends Message{

    private int clientPort;
    private int messageId;
    private String sender;
    private String text;
    private int stickerId;


    public ClientAddMessage(int clientPort, int messageId, String sender, String text, int stickerId) {
        this.clientPort = clientPort;
        this.messageId = messageId;
        this.sender = sender;
        this.text = text;
        this.stickerId = stickerId;
        this.messageType = MessageType.CLIENT_ADD_MESSAGE;
    }
    public String printValues() { return clientPort + " " + messageId + " " + sender + " " + text ;}
}
