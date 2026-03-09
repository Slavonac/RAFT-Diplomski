package raf.rs.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raf.rs.RPC.AppendEntries;
import raf.rs.RPC.ClientAddMessage;
import raf.rs.RPC.RequestVote;
import raf.rs.log.LogEntry;
import raf.rs.log.StateMachine;
import raf.rs.log.commands.AddMessageCommand;
import raf.rs.util.NodeState;

import java.io.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Node {

    private int port;

    private final Thread socketThread;
    private final MessageSender ms;
    private final ScheduledExecutorService timeoutScheduler;
    private final ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> task;

    private int currentTerm;
    private int votedFor;
    private int leaderPort;

    private final List<LogEntry> log;

    private int commitIndex;
    private int lastApplied;

    private String[] nextIndex;
    private String[] matchIndex;

    private StateMachine stateMachine;

    private final AtomicInteger voteCount = new AtomicInteger(0);
    private static final Logger logger = LoggerFactory.getLogger(Node.class);

    private NodeState nodeState = NodeState.FOLLOWER;
    private final Object stateLock = new Object();

    public Node(int nodeNum) {

        // Node configuration
        port = -1;
        List<Integer> nodes = new ArrayList<>();

        Properties props = new Properties();
        try (InputStream in = new FileInputStream("io/config.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        int numberOfNodes = Integer.parseInt(props.getProperty("nodes"));

        for (int i = 0; i < numberOfNodes; i++) {
            int portAddress = Integer.parseInt(props.getProperty("node" + i));
            if(i == nodeNum) port = portAddress; else nodes.add(portAddress);
        }

        // Node values
        currentTerm = 0;
        votedFor = -1;
        log = new ArrayList<>();

        // Thread for receiving messages
        SocketListener sl = new SocketListener(port, this);
        socketThread = new Thread(sl);
        socketThread.start();

        // Sending messages
        ms = new MessageSender(nodes, this);

        // Scheduler for node timeout
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler = Executors.newScheduledThreadPool(4);
        startTimeoutTimer();

    }

    // Timeout mechanism
    private void startTimeoutTimer() {
        Random r = new Random();
        task = timeoutScheduler.schedule(() -> {
            if (nodeState.equals(NodeState.LEADER)) return;
            logger.info("NODE TIMEOUT");
            synchronized (this) {
                ++this.currentTerm;
                this.nodeState = NodeState.CANDIDATE;
                this.votedFor = this.port;
                this.voteCount.incrementAndGet();
            }
            resetElectionTimeout();
            ms.requestVote();
        }, 150 + r.nextInt(150), TimeUnit.MILLISECONDS);
    }

    public synchronized void resetElectionTimeout() {
        if(!task.isDone())
            task.cancel(true);
        startTimeoutTimer();
    }

    // Voting

    public void requestVoteRPC(RequestVote message) {
        synchronized (this) {

            // Candidate's term is lower than the current server term, rejects the request
            if (message.getTerm() < this.currentTerm){
                ms.submitVote(message.getCandidateId(), currentTerm, false);
                return;
            }
            // If this node is leader/candidate with lower term, revert to follower
            if (message.getTerm() > this.currentTerm)
                this.nodeState = NodeState.FOLLOWER;
            // Valid request received, timeout timer restarted
            resetElectionTimeout();
            // New term, reset vote
            if (message.getTerm() != this.currentTerm) {
                this.votedFor = -1;
            }
            // Update term from a message
            this.currentTerm = message.getTerm();
            // Already voted, send false
            if(votedFor != -1) {
                ms.submitVote(message.getCandidateId(), currentTerm, false);
                return;
            }

            // TODO: Glasati ne ako se log poklapa lose

            this.votedFor = message.getCandidateId();
            ms.submitVote(message.getCandidateId(),  this.currentTerm, true);
        }
    }

    public void voteResult(boolean voteGranted, int term) {
        if (nodeState != NodeState.CANDIDATE)
            return;
        if (term > this.currentTerm) {
            this.nodeState = NodeState.FOLLOWER;
            this.currentTerm = term;
            return;
        }
        if (voteGranted) {
            int count = this.voteCount.incrementAndGet();
            if (count > 2) {
                becomeLeader();
            }
        }
    }

    // AppendEntries
    public void appendEntries(AppendEntries entries) {
        this.leaderPort = entries.getLeaderId();
        this.resetElectionTimeout();
    }

    public void addClientRequest(ClientAddMessage message) {
        LogEntry logEntry = new LogEntry(this.currentTerm, new AddMessageCommand(message));
        this.log.add(logEntry);
    }


    public void becomeLeader() {
        if(this.nodeState.equals(NodeState.LEADER))
            return;
        this.nodeState = NodeState.LEADER;
        //this.voteCount.set(0);
        logger.info("Leader elected");
        this.leaderPort = this.port;
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            ms.appendEntries(this.currentTerm, this.leaderPort);
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    public void sendLeaderPort(int port) throws IOException {
        ms.giveLeaderAddress(port);
    }

    // Stops nodes
    public void stopNode() {
        timeoutScheduler.shutdownNow();
        heartbeatScheduler.shutdownNow();
        socketThread.interrupt();
        ms.closeExec();
        ms.closeActiveSockets();
    }

    public int getPort() { return port; }
    public int getTerm() { return this.currentTerm; }
    public int getLeaderPort() { return this.leaderPort; }

}
