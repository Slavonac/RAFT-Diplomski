package raf.rs.node;

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raf.rs.RPC.*;
import raf.rs.log.StateMachine;
import raf.rs.log.commands.AddMessageCommand;
import raf.rs.util.NodeState;

import java.io.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Node {

    private int port;

    private final ScheduledExecutorService timeoutScheduler;
    private final ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> task;
    private final Map<Integer, ManagedChannel> managedChannelMap;
    private final Map<Integer, RAFTGrpc.RAFTStub> stubMap;

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
    private final Object stateChange = new Object();

    public Node(int nodeNum) throws IOException {

        // Node configuration
        port = -1;
        managedChannelMap = new HashMap<>();
        stubMap = new HashMap<>();

        Properties props = new Properties();
        try (InputStream in = new FileInputStream("io/config.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        int numberOfNodes = Integer.parseInt(props.getProperty("nodes"));

        for (int i = 0; i < numberOfNodes; i++) {
            int portAddress = Integer.parseInt(props.getProperty("node" + i));
            if(i == nodeNum) port = portAddress;
            else {
                ManagedChannel channel = Grpc.newChannelBuilder("localhost:" + portAddress, InsecureChannelCredentials.create()).build();
                RAFTGrpc.RAFTStub stub = RAFTGrpc.newStub(channel);
                managedChannelMap.put(portAddress, channel);
                stubMap.put(portAddress, stub);
            }
        }
        Server server = ServerBuilder.forPort(port).addService(new NodeRPCService(this)).build().start();

        // Node values
        currentTerm = 0;
        votedFor = -1;
        log = new ArrayList<>();

        // Scheduler for node timeout
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler = Executors.newScheduledThreadPool(4);
        startTimeoutTimer();
    }

    // Timeout mechanism
    private void startTimeoutTimer() {
        Random r = new Random();
        task = timeoutScheduler.schedule(() -> {
            synchronized (stateChange) {
                if (nodeState.equals(NodeState.LEADER)) return;
                ++this.currentTerm;
                this.nodeState = NodeState.CANDIDATE;
                this.votedFor = this.port;
                this.voteCount.set(1);
            }
            logger.info("NODE TIMEOUT CurrTerm:" + this.currentTerm);
            resetElectionTimeout();
            requestVote();
        }, 150 + r.nextInt(150), TimeUnit.MILLISECONDS);
    }
    public synchronized void resetElectionTimeout() {
        if(!task.isDone())
            task.cancel(true);
        startTimeoutTimer();
    }

    private void requestVote() {
        for (Integer port : stubMap.keySet()){
            RequestVoteReq req = RequestVoteReq.newBuilder()
                    .setTerm(currentTerm)
                    .setLastLogIndex(getLastLogIndex())
                    .setLastLogTerm(getLastLogTerm())
                    .setCandidateId(this.port).build();
            logger.info("Requesting vote for node: " + port);
            stubMap.get(port).requestVote(req, new StreamObserver<>() {
                @Override
                public void onNext(RequestVoteRes res) {
                    voteResult(res.getVoteGranted(), (int) res.getTerm(), port);
                }
                @Override
                public void onError(Throwable throwable) { System.err.println("RPC failed " + throwable.getMessage()); }
                @Override
                public void onCompleted() {}
            });
        }
    }

    // Voting
    public void voteResult(boolean voteGranted, int term, int port) {
        if (nodeState != NodeState.CANDIDATE)
            return;
        if (term > this.currentTerm) {
            logger.info("Node: " + port + " is higher term: " + term + " Old term is: " + this.currentTerm + " reverting to Follower");
            synchronized (stateChange) {
                this.nodeState = NodeState.FOLLOWER;
                this.currentTerm = term;
            }
            return;
        }
        if (voteGranted && term == this.currentTerm) {
            logger.info("Vote granted by node: " + port + " in term: " + term);
            int count = this.voteCount.incrementAndGet();
            if (count > (stubMap.size() + 1) / 2) {
                becomeLeader();
            }
        }
    }

    public void becomeLeader() {
        synchronized (stateChange) {
            if(this.nodeState.equals(NodeState.LEADER))
                return;
            this.nodeState = NodeState.LEADER;
        }
        //this.voteCount.set(0);
        logger.info("Leader elected");
        this.leaderPort = this.port;
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            for (Integer port : stubMap.keySet()) {
                LogEntry logEntry = LogEntry.newBuilder().build();
                AppendEntriesReq req = AppendEntriesReq.newBuilder()
                        .setTerm(currentTerm)
                        .setLeaderId(this.port)
                        .setPrevLogIndex(0)
                        .setPrevLogTerm(0)
                        //.setEntries(1, logEntry)
                        .setLeaderCommit(commitIndex)
                        .build();
                stubMap.get(port).appendEntries(req, new StreamObserver<>() {
                    @Override
                    public void onNext(AppendEntriesRes res) {
                        synchronized (stateChange) {
                            if (res.getTerm() > currentTerm){
                                nodeState = NodeState.FOLLOWER;
                                logger.info("There is a node with higher term " + res.getTerm() + " > " + currentTerm + ". reverting to follower");
                            }
                        }
                    }
                    @Override
                    public void onError(Throwable throwable) {}

                    @Override
                    public void onCompleted() {}
                });
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    public int getLastLogIndex() {
        int lastIndex = 0;
        if(!log.isEmpty()){
            lastIndex = (int) log.getLast().getIndex();
        }
        return lastIndex;
    }
    public int getLastLogTerm() {
        int lastTerm = 0;
        if(!log.isEmpty()){
            lastTerm = (int) log.getLast().getTerm();
        }
        return lastTerm;
    }
    public int getPort() { return port; }
    public int getTerm() { return this.currentTerm; }
    public void setCurrentTerm(int currentTerm) { this.currentTerm = currentTerm; }
    public int getVotedFor() { return votedFor; }
    public void setVotedFor(int votedFor) { this.votedFor = votedFor; }
    public NodeState getNodeState() { return nodeState; }
    public void setNodeState(NodeState nodeState) { this.nodeState = nodeState; }
    public Object getStateChange() { return stateChange; }

    public void shutdown() {
        heartbeatScheduler.shutdownNow();
        timeoutScheduler.shutdown();
        task.cancel(true);
        for (Integer port : this.managedChannelMap.keySet()) {
            this.managedChannelMap.get(port).shutdownNow();
        }
    }
}