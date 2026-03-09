package raf.rs.RPC;

public class RequestVote extends Message{

    private int term;
    private int candidateId;
    private int lastLogIndex;
    private int lastLogTerm;

    public RequestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) {
        this.term = term;
        this.candidateId = candidateId;
        this.lastLogIndex = lastLogIndex;
        this.lastLogTerm = lastLogTerm;
        this.messageType = MessageType.REQUEST_VOTE;
    }

    public int getTerm() {
        return term;
    }

    public int getCandidateId() {
        return candidateId;
    }

    public int getLastLogIndex() {
        return lastLogIndex;
    }

    public int getLastLogTerm() {
        return lastLogTerm;
    }
    public String getValues() {
        return term + " " + candidateId + " " + lastLogIndex + " " + lastLogTerm;
    }

}
