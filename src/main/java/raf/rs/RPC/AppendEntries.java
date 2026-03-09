package raf.rs.RPC;

import java.util.List;

public class AppendEntries extends Message{

    int term;
    int leaderId;
    int prevLogIndex;
    int prevLogTerm;
    List<String> entries;
    int leaderCommit;

    public AppendEntries (int term, int leaderPort) {
        this.term = term;
        this.leaderId = leaderPort;
        this.messageType = MessageType.APPEND_ENTRIES;
    }

    public int getTerm() {
        return term;
    }

    public int getLeaderId() {
        return leaderId;
    }

    public int getPrevLogIndex() {
        return prevLogIndex;
    }

    public int getPrevLogTerm() {
        return prevLogTerm;
    }

    public List<String> getEntries() {
        return entries;
    }

    public int getLeaderCommit() {
        return leaderCommit;
    }
}
