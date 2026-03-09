package raf.rs.RPC;

public class LeaderPort extends Message{
    int leaderPort;

    public LeaderPort(int leaderPort) {
        this.leaderPort = leaderPort;
        this.messageType = MessageType.LEADER_PORT;
    }

    public int getLeaderPort() {
        return leaderPort;
    }
}
