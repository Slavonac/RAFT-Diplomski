package raf.rs.node;

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raf.rs.RPC.*;
import raf.rs.log.Log;
import raf.rs.log.StateMachine;
import raf.rs.util.NodeState;

import java.io.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Node {

    private int nodeId;
    private final int clusterSize;
    private int port;

    private final Server server;
    private final ScheduledExecutorService timeoutScheduler;
    private final ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> task;
    private final Map<Integer, ManagedChannel> managedChannelMap;
    private final Map<Integer, RAFTGrpc.RAFTStub> stubMap;
    private final Map<Integer, Integer> portNodeIdMap;
    private final int timeoutTime;
    private final int heartbeatTime;

    private int currentTerm;
    private int votedFor;
    private int leaderPort;

    private final Log log;

    private int commitIndex;
    private int lastApplied;

    private final List<Integer> nextIndex;
    private final List<Integer> matchIndex;

    private final StateMachine stateMachine;

    private final AtomicInteger voteCount = new AtomicInteger(0);
    private static final Logger logger = LoggerFactory.getLogger(Node.class);

    private NodeState nodeState = NodeState.FOLLOWER;
    private final Object stateChange = new Object();

    public static void start(Integer nodeNum) throws IOException {
        new Node(nodeNum);
    }

    private Node(int nodeNum) throws IOException {
        // Node configuration
        port = -1;
        managedChannelMap = new HashMap<>();
        stubMap = new HashMap<>();
        portNodeIdMap = new HashMap<>();
        timeoutTime = 150;
        heartbeatTime = 50;

        Properties props = new Properties();
        try (InputStream in = new FileInputStream("io/config.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        clusterSize = Integer.parseInt(props.getProperty("nodes"));
        for (int i = 0; i < clusterSize; i++) {
            int portAddress = Integer.parseInt(props.getProperty("node" + i));
            portNodeIdMap.put(portAddress, i);
            if(i == nodeNum) {
                nodeId = i;
                port = portAddress;
            }
            else {
                ManagedChannel channel = Grpc.newChannelBuilder(props.getProperty("host") + ":" + portAddress, InsecureChannelCredentials.create()).build();
                RAFTGrpc.RAFTStub stub = RAFTGrpc.newStub(channel);
                managedChannelMap.put(portAddress, channel);
                stubMap.put(portAddress, stub);
            }
        }
        server = ServerBuilder.forPort(port).addService(new NodeRPCService(this)).build().start();

        // Node values
        nextIndex = new ArrayList<>();
        matchIndex = new ArrayList<>();
        for (int i = 0; i < clusterSize; i++) {
            nextIndex.add(1);
            matchIndex.add(0);
        }
       // logger.info(Arrays.toString(nextIndex.toArray()) + " " + Arrays.toString(matchIndex.toArray()));
        commitIndex = 0;
        lastApplied = 0;
        currentTerm = 0;
        votedFor = -1;
        log = new Log();
        stateMachine = new StateMachine();

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
        }, timeoutTime + r.nextInt(150), TimeUnit.MILLISECONDS);
    }
    public synchronized void resetElectionTimeout() {
        if(!task.isDone())
            task.cancel(true);
        startTimeoutTimer();
    }
    // Optimization needed, can't do this whole synchronized
    public synchronized void addEntry(Command command) {
        LogEntry entry = LogEntry.newBuilder()
                .setTerm(this.getTerm())
                .setIndex(this.log.getIndexForNextEntry())
                .setCommand(Command.newBuilder().setAddCommand(command.getAddCommand())).build();
        this.log.add(entry);
        matchIndex.set(nodeId, matchIndex.get(nodeId) + 1);
        logger.info("Adding command to the log");
        appendEntry(entry);
    }
    private void appendEntry(LogEntry entry) {
        for (Integer port : stubMap.keySet()) {
            AppendEntriesReq req = AppendEntriesReq.newBuilder()
                    .setTerm(currentTerm)
                    .setLeaderId(this.port)
                    .setLeaderCommit(this.commitIndex)
                    .setPrevLogTerm(getPrevLogTerm(port))
                    .setPrevLogIndex(getPrevLogIndex(port))
                    .setEntry(entry)
                    .build();
            Context.current().fork().run(() -> stubMap.get(port).appendEntries(req, new StreamObserver<>() {
                @Override
                public void onNext(AppendEntriesRes res) {
                    if (res.getSuccess()) {
                        logger.info("Successfully replicated log to node: " + port);
                        int nodeNumer = portNodeIdMap.get(port);
                        nextIndex.set(nodeNumer, nextIndex.get(nodeNumer) + 1);
                        matchIndex.set(nodeNumer, matchIndex.get(nodeNumer) + 1);
                        updateCommitIndex(); 
                    } else {
                        logger.info("Unsuccessfully replicated log");
                        int index = portNodeIdMap.get(port);
                        nextIndex.set(index, nextIndex.get(index) - 1);
                    }
                }
                @Override
                public void onError(Throwable throwable) {
                    System.out.println(throwable.getMessage());
                }
                @Override
                public void onCompleted() {}
            }));
        }
    }
    public synchronized void updateCommitIndex() {
        int count = 0;
        for (int i = 0; i < matchIndex.size() - 1;  i++) {
            if (matchIndex.get(i) > commitIndex) count++;
        }
        if (count > (matchIndex.size() / 2) + 1){
            commitIndex++;
            logger.info("Commit index increaded...Applying to state machine");
            stateMachine.applyCommand(log.getCommand(commitIndex));
            logger.info("Applied to the state machine");
            lastApplied = commitIndex;
        }
    }
    // Voting mechanism
    private void requestVote() {
        for (Integer port : stubMap.keySet()){
            Context.current().fork().run(() -> {
                RequestVoteReq req = RequestVoteReq.newBuilder()
                        .setTerm(currentTerm)
                        .setLastLogIndex(0)
                        .setLastLogTerm(0)
                        .setCandidateId(this.port).build();
                logger.info("Requesting vote for node: " + port);
                stubMap.get(port).requestVote(req, new StreamObserver<>() {
                    @Override
                    public void onNext(RequestVoteRes res) {
                        voteResult(res.getVoteGranted(), (int) res.getTerm(), port);
                    }
                    @Override
                    public void onError(Throwable throwable) { System.err.println("RPC vote request failed " + throwable.getMessage()); }
                    @Override
                    public void onCompleted() {}
                });
            });
        }
    }
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
        logger.info("Leader elected");
        this.leaderPort = this.port;
        for (int i = 0; i < stubMap.size(); i++) {
            // Replace with next log index
            nextIndex.set(i, 1);
            matchIndex.set(i, 0);
        }

        heartbeatScheduler.scheduleAtFixedRate(() -> {
            for (Integer port : stubMap.keySet()) {
                AppendEntriesReq req = AppendEntriesReq.newBuilder()
                        .setTerm(currentTerm)
                        .setLeaderId(this.port)
                        .setPrevLogIndex(0)
                        .setPrevLogTerm(0)
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
                    public void onError(Throwable throwable) {
                        logger.error(throwable.getMessage());
                    }
                    @Override
                    public void onCompleted() {}
                });
            }
        }, 0, heartbeatTime, TimeUnit.MILLISECONDS);
    }
    // Separating for clearer code
    // Extracting which index belongs to node port from nextIndex, and getting correct previous index and term
    private int getPrevLogIndex(int port) {
        return log.getPreviousLogIndex(nextIndex.get(portNodeIdMap.get(port)));
    }
    private int getPrevLogTerm(int port) {
        return log.getPreviousLogTerm(nextIndex.get(portNodeIdMap.get(port)));
    }

    public int getPort() { return port; }
    public int getTerm() { return this.currentTerm; }
    public void setCurrentTerm(int currentTerm) { this.currentTerm = currentTerm; }
    public int getVotedFor() { return votedFor; }
    public void setVotedFor(int votedFor) { this.votedFor = votedFor; }
    public NodeState getNodeState() { return nodeState; }
    public void setNodeState(NodeState nodeState) { this.nodeState = nodeState; }
    public Object getStateChange() { return stateChange; }
    public void setLeaderPort(int leaderPort) { this.leaderPort = leaderPort; }
    public int getLeaderPort() { return this.leaderPort; }
    public void addReplicatedLog (LogEntry entry) { this.log.add(entry); }
    public boolean checkIfPrevLogMatches (int term, int index) { return this.log.checkIfPrevLogMatches(term, index); }

    public void shutdown() {
        server.shutdownNow();
        heartbeatScheduler.shutdownNow();
        timeoutScheduler.shutdown();
        task.cancel(true);
        for (Integer port : this.managedChannelMap.keySet()) {
            this.managedChannelMap.get(port).shutdownNow();
        }
    }
}