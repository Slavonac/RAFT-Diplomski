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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Node {

    private int nodeId;
    private final int clusterSize;
    private String myAddress;

    private final Server server;
    private final PauseInterceptor pauseInterceptor;
    private final ScheduledExecutorService timeoutScheduler;
    private final AtomicBoolean heartBeatPaused;
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
    private final AtomicBoolean commandsLocked = new AtomicBoolean(false);
    private static final Logger logger = LoggerFactory.getLogger(Node.class);
    private boolean logEnabled = false;

    private NodeState nodeState = NodeState.FOLLOWER;
    private final Object stateChange = new Object();
    private final Object commitLock = new Object();

    private void log(String msg) {
        if (logEnabled) logger.info(msg);
    }

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
        pauseInterceptor = new PauseInterceptor();
        server = ServerBuilder.forPort(Integer.parseInt(myAddress.split(":")[1]))
                .intercept(pauseInterceptor)
                .addService(new NodeRPCService(this))
                .build()
                .start();
        logEnabled = System.getenv("LOG_ENBALED").equals("true");
        // Node values
        nextIndex = new ArrayList<>();
        matchIndex = new ArrayList<>();
        for (int i = 0; i < clusterSize; i++) {
            nextIndex.add(1);
            matchIndex.add(0);
        }
        commitIndex = 0;
        lastApplied = 0;
        currentTerm = 0;
        votedFor = "";
        log = new Log();
        stateMachine = new StateMachine();

        // Scheduler for node timeout
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler = Executors.newScheduledThreadPool(4);
        heartBeatPaused = new AtomicBoolean(false);
        startTimeoutTimer();
    }
    // Timeout mechanism
    private void startTimeoutTimer() {
        Random r = new Random();
        task = timeoutScheduler.schedule(() -> {
            if (pauseInterceptor.isPaused()) return;
            synchronized (stateChange) {
                if (nodeState.equals(NodeState.LEADER)) return;
                ++this.currentTerm;
                this.nodeState = NodeState.CANDIDATE;
                this.votedFor = this.myAddress;
                this.voteCount.set(1);
            }
            log("NODE TIMEOUT CurrTerm:" + this.currentTerm);
            resetElectionTimeout();
            requestVote();
        }, timeoutTime + r.nextInt(150), TimeUnit.MILLISECONDS);
    }
    public synchronized void resetElectionTimeout() {
        if(!task.isDone())
            task.cancel(true);
        startTimeoutTimer();
    }
    public synchronized int addEntry(Command command) {
        if (commandsLocked.get()) return -1;
        LogEntry entry = LogEntry.newBuilder()
                .setTerm(this.getTerm())
                .setIndex(this.log.getIndexForNextEntry())
                .setCommand(command)
                .build();
        this.log.add(List.of(entry));
        int entryIndex = log.getLastEntryIndex();
        matchIndex.set(nodeId, entryIndex);
        log("Adding command to the log at index " + entryIndex);
        for (String address : stubMap.keySet()) {
            replicateTo(address);
        }
        return entryIndex;
    }

    public void clearAllMessages() {
        commandsLocked.set(true);
        try {
            stateMachine.clearMessages();
            clearLog();
            for (String address : stubMap.keySet()) {
                Context.current().fork().run(() ->
                    stubMap.get(address).clearMessages(
                        ClearMessagesReq.newBuilder().build(),
                        new StreamObserver<ClearMessagesRes>() {
                            @Override public void onNext(ClearMessagesRes r) {}
                            @Override public void onError(Throwable t) {}
                            @Override public void onCompleted() {}
                        })
                );
            }
        } finally {
            commandsLocked.set(false);
        }
    }

    public synchronized void clearLog() {
        log.clear();
        commitIndex = 0;
        lastApplied = 0;
        for (int i = 0; i < nextIndex.size(); i++) {
            nextIndex.set(i, 1);
            matchIndex.set(i, 0);
        }
    }

    public boolean isCommandsLocked() { return commandsLocked.get(); }
    private void replicateTo(String address) {
        int followerIndex = portNodeIdMap.get(address);
        int prevLogIdx = getPrevLogIndex(address);
        int prevLogTrm = getPrevLogTerm(address);
        List<LogEntry> entries = log.getEntriesFrom(nextIndex.get(followerIndex));

        AppendEntriesReq req = AppendEntriesReq.newBuilder()
                .setTerm(currentTerm)
                .setLeaderId(portNodeIdMap.get(myAddress))
                .setLeaderCommit(commitIndex)
                .setPrevLogIndex(prevLogIdx)
                .setPrevLogTerm(prevLogTrm)
                .addAllEntry(entries)
                .build();

        Context.current().fork().run(() -> stubMap.get(address).appendEntries(req, new StreamObserver<>() {
            @Override
            public void onNext(AppendEntriesRes res) {
                synchronized (stateChange) {
                    if (res.getTerm() > currentTerm) {
                        currentTerm = (int) res.getTerm();
                        nodeState = NodeState.FOLLOWER;
                        heartBeatPaused.set(true);
                        log("Cant append entries to a higher term node: " + address + ". Reverting to follower.");
                        resetElectionTimeout();
                        return;
                    }
                }
                if (res.getSuccess()) {
                    if (!entries.isEmpty()) {
                        int lastSentIndex = (int) entries.get(entries.size() - 1).getIndex();
                        nextIndex.set(followerIndex, lastSentIndex + 1);
                        matchIndex.set(followerIndex, lastSentIndex);
                        log("Replicated to " + address + " up to index " + lastSentIndex);
                        updateCommitIndex();
                    }
                } else {
                    int current = nextIndex.get(followerIndex);
                    if (current > 1) {
                        nextIndex.set(followerIndex, current - 1);
                        log("Decrementing nextIndex for " + address + " to " + (current - 1));
                    }
                }
            }
            @Override
            public void onError(Throwable throwable) {}
            @Override
            public void onCompleted() {}
        }));
    }
    private void activateHeartbeatScheduler() {
        heartBeatPaused.set(false);
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (heartBeatPaused.get() || !nodeState.equals(NodeState.LEADER)) return;
            for (String address : stubMap.keySet()) {
                replicateTo(address);
            }
        }, 0, heartbeatTime, TimeUnit.MILLISECONDS);
    }

    // Voting mechanism
    private void requestVote() {
        for (String address : stubMap.keySet()){
            Context.current().fork().run(() -> {
                RequestVoteReq req = RequestVoteReq.newBuilder()
                        .setTerm(currentTerm)
                        .setLastLogIndex(log.getLastEntryIndex())
                        .setLastLogTerm(log.getLastEntryTerm())
                        .setCandidateId(portNodeIdMap.get(myAddress)).build();
                log("Requesting vote for node: " + address);
                stubMap.get(address).requestVote(req, new StreamObserver<>() {
                    @Override
                    public void onNext(RequestVoteRes res) {
                        voteResult(res.getVoteGranted(), (int) res.getTerm(), address);
                    }
                    @Override
                    public void onError(Throwable throwable) {}
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
            log("Node: " + address + " is higher term: " + term + " Old term is: " + this.currentTerm + " reverting to Follower");
            synchronized (stateChange) {
                this.nodeState = NodeState.FOLLOWER;
                this.currentTerm = term;
            }
            return;
        }
        if (voteGranted && this.currentTerm == term) {
            log("Vote granted by node: " + address + " in term: " + term);
            if (this.voteCount.incrementAndGet() > (stubMap.size() + 1) / 2) {
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
        log("Leader elected");
        this.leaderPort = this.myAddress;
        for (String address : stubMap.keySet()) {
            int followerIdx = portNodeIdMap.get(address);
            nextIndex.set(followerIdx, log.getLastEntryIndex() + 1);
            matchIndex.set(followerIdx, 0);
        }
        activateHeartbeatScheduler();
    }

    public synchronized void updateCommitIndex() {
        if (log.getLastEntryTerm() < currentTerm) return;
        boolean advanced = false;
        for (int n = commitIndex + 1; n <= log.getLastEntryIndex(); n++) {
            if (log.getTermAtIndex(n) != currentTerm) continue;
            int count = 1;
            for (String address : stubMap.keySet()) {
                int followerIdx = portNodeIdMap.get(address);
                if (matchIndex.get(followerIdx) >= n) count++;
            }
            if (count > clusterSize / 2) {
                commitIndex = n;
                log("Commit index advanced to " + n + ". Applying to state machine.");
                stateMachine.applyCommand(log.getCommand(n), n);
                lastApplied = n;
                advanced = true;
            } else {
                break;
            }
        }
        if (advanced) {
            synchronized (commitLock) {
                commitLock.notifyAll();
            }
        }
    }

    public boolean waitForCommit(int entryIndex, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (commitLock) {
            while (commitIndex < entryIndex) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false;
                try {
                    commitLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    public void commitEntriesToCommitIndex(long leaderCommit) {
        synchronized (commitLock) {
            if (leaderCommit > commitIndex) {
                commitIndex = (int) Math.min(leaderCommit, log.getLastEntryIndex());
            }
            while (lastApplied < commitIndex) {
                int next = ++lastApplied;
                stateMachine.applyCommand(log.getCommand(next), next);
            }
        }
    }

    // Separating for cleaner code
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
    public void pause() {
        pauseInterceptor.setPaused(true);
        if (!task.isDone()) task.cancel(true);
        heartBeatPaused.set(true);
        log("Node " + nodeId + " paused (simulating failure)");
    }

    public void resume() {
        pauseInterceptor.setPaused(false);
        heartBeatPaused.set(false);
        log("Node " + nodeId + " resumed");
    }

    public void shutdown() {
        server.shutdownNow();
        heartbeatScheduler.shutdownNow();
        timeoutScheduler.shutdown();
        task.cancel(true);
        for (String port : this.managedChannelMap.keySet()) {
            this.managedChannelMap.get(port).shutdownNow();
        }
    }
    public List<Message> getMessages() { return this.stateMachine.getMessages(); }
    public raf.rs.log.StateMachine getStateMachine() { return this.stateMachine; }
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
    public void replicateLogEntries(int prevLogIndex, List<LogEntry> entries) {
        log.truncateFrom(prevLogIndex + 1);
        if (!entries.isEmpty()) {
            log.add(entries);
        }
    }
    public boolean checkIfPrevLogMatches (int prevLogTerm, int prevLogIndex) { return this.log.checkIfPrevLogMatches(prevLogTerm, prevLogIndex); }
    public int getLastEntryTerm() { return log.getLastEntryTerm(); }
    public int getLastEntryIndex() { return log.getLastEntryIndex(); }
    public int getCommitIndex() { return this.commitIndex; }
    public List<LogEntry> getLogEntries() { return this.log.getEntriesFrom(1); }
    public void pauseHearbeat() { this.heartBeatPaused.set(true); }
}