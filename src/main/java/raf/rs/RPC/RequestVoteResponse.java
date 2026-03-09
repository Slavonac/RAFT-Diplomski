package raf.rs.RPC;

public class RequestVoteResponse extends Message {

    private int term;
    private boolean voteGranted;
    private int nodePort;

    public RequestVoteResponse(int term, boolean voteGranted) {
        this.term = term;
        this.voteGranted = voteGranted;
        this.messageType = MessageType.REQUEST_VOTE_RESPONSE;
    }

    public int getTerm() {
        return term;
    }

    public int getNodePort() {
        return nodePort;
    }

    public void setNodePort(int nodePort) {
        this.nodePort = nodePort;
    }

    public boolean isVoteGranted() {
        return voteGranted;
    }
}
