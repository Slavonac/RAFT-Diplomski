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
    private String myAddress;

    private final Server server;
    private final ScheduledExecutorService timeoutScheduler;
    private final ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> task;
    private final Map<String, ManagedChannel> managedChannelMap;
    private final Map<String, RAFTGrpc.RAFTStub> stubMap;
    private final Map<String, Integer> portNodeIdMap;
    private final int timeoutTime;
    private final int heartbeatTime;

    private int currentTerm;
    private String votedFor;
    private String leaderPort;

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
        myAddress = "";
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
        String nodesEnv = System.getenv("NODES");
        clusterSize = nodesEnv != null ? Integer.parseInt(nodesEnv) : Integer.parseInt(props.getProperty("nodes"));
        for (int i = 0; i < clusterSize; i++) {
            String envAddr = System.getenv("NODE_" + i);
            String nodeAddres = envAddr != null ? envAddr : props.getProperty("node" + i);
            portNodeIdMap.put(nodeAddres, i);
            if(i == nodeNum) {
                nodeId = i;
                myAddress = nodeAddres;
            }
            else {
                ManagedChannel channel = Grpc.newChannelBuilder(nodeAddres, InsecureChannelCredentials.create()).build();
                RAFTGrpc.RAFTStub stub = RAFTGrpc.newStub(channel);
                managedChannelMap.put(nodeAddres, channel);
                stubMap.put(nodeAddres, stub);
            }
        }
        server = ServerBuilder.forPort(Integer.parseInt(myAddress.split(":")[1])).addService(new NodeRPCService(this)).build().start();
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
        votedFor = "";
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
                this.votedFor = this.myAddress;
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
        for (String address : stubMap.keySet()) {
            AppendEntriesReq req = AppendEntriesReq.newBuilder()
                    .setTerm(currentTerm)
                    .setLeaderId(portNodeIdMap.get(address))
                    .setLeaderCommit(this.commitIndex)
                    .setPrevLogTerm(getPrevLogTerm(address))
                    .setPrevLogIndex(getPrevLogIndex(address))
                    .setEntry(entry)
                    .build();
            Context.current().fork().run(() -> stubMap.get(address).appendEntries(req, new StreamObserver<>() {
                @Override
                public void onNext(AppendEntriesRes res) {
                    if (res.getSuccess()) {
                        logger.info("Successfully replicated log to node: " + address);
                        int nodeNumer = portNodeIdMap.get(address);
                        nextIndex.set(nodeNumer, nextIndex.get(nodeNumer) + 1);
                        matchIndex.set(nodeNumer, matchIndex.get(nodeNumer) + 1);
                        updateCommitIndex(); 
                    } else {
                        logger.info("Unsuccessfully replicated log");
                        int index = portNodeIdMap.get(address);
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
        for (String address : stubMap.keySet()){
            Context.current().fork().run(() -> {
                RequestVoteReq req = RequestVoteReq.newBuilder()
                        .setTerm(currentTerm)
                        .setLastLogIndex(0)
                        .setLastLogTerm(0)
                        .setCandidateId(portNodeIdMap.get(myAddress)).build();
                logger.info("Requesting vote for node: " + address);
                stubMap.get(address).requestVote(req, new StreamObserver<>() {
                    @Override
                    public void onNext(RequestVoteRes res) {
                        voteResult(res.getVoteGranted(), (int) res.getTerm(), address);
                    }
                    @Override
                    public void onError(Throwable throwable) { System.err.println("RPC vote request failed " + throwable.getMessage()); }
                    @Override
                    public void onCompleted() {}
                });
            });
        }
    }
    public void voteResult(boolean voteGranted, int term, String address) {
        if (nodeState != NodeState.CANDIDATE)
            return;
        if (term > this.currentTerm) {
            logger.info("Node: " + address + " is higher term: " + term + " Old term is: " + this.currentTerm + " reverting to Follower");
            synchronized (stateChange) {
                this.nodeState = NodeState.FOLLOWER;
                this.currentTerm = term;
            }
            return;
        }
        if (voteGranted && term == this.currentTerm) {
            logger.info("Vote granted by node: " + address + " in term: " + term);
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
        this.leaderPort = this.myAddress;
        for (int i = 0; i < stubMap.size(); i++) {
            // Replace with next log index
            nextIndex.set(i, 1);
            matchIndex.set(i, 0);
        }

        heartbeatScheduler.scheduleAtFixedRate(() -> {
            for (String address : stubMap.keySet()) {
                AppendEntriesReq req = AppendEntriesReq.newBuilder()
                        .setTerm(currentTerm)
                        .setLeaderId(this.portNodeIdMap.get(myAddress))
                        .setPrevLogIndex(0)
                        .setPrevLogTerm(0)
                        .setLeaderCommit(commitIndex)
                        .build();
                stubMap.get(address).appendEntries(req, new StreamObserver<>() {
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
    private int getPrevLogIndex(String address) {
        return log.getPreviousLogIndex(nextIndex.get(portNodeIdMap.get(address)));
    }
    private int getPrevLogTerm(String address) {
        return log.getPreviousLogTerm(nextIndex.get(portNodeIdMap.get(address)));
    }

    public String addressFromNodeId(long leaderId) {
        return portNodeIdMap.entrySet().stream()
                .filter(e -> e.getValue().longValue() == leaderId)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("");
    }

    public String getPort() { return myAddress; }
    public int getTerm() { return this.currentTerm; }
    public void setCurrentTerm(int currentTerm) { this.currentTerm = currentTerm; }
    public String getVotedFor() { return votedFor; }
    public void setVotedFor(String votedFor) { this.votedFor = votedFor; }
    public NodeState getNodeState() { return nodeState; }
    public void setNodeState(NodeState nodeState) { this.nodeState = nodeState; }
    public Object getStateChange() { return stateChange; }
    public void setLeaderPort(String leaderPort) { this.leaderPort = leaderPort; }
    public String getLeaderPort() { return this.leaderPort; }
    public void addReplicatedLog (LogEntry entry) { this.log.add(entry); }
    public boolean checkIfPrevLogMatches (int term, int index) { return this.log.checkIfPrevLogMatches(term, index); }

    public void shutdown() {
        server.shutdownNow();
        heartbeatScheduler.shutdownNow();
        timeoutScheduler.shutdown();
        task.cancel(true);
        for (String port : this.managedChannelMap.keySet()) {
            this.managedChannelMap.get(port).shutdownNow();
        }
    }


}