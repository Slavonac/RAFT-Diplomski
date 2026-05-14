package raf.rs.node;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raf.rs.RPC.*;
import raf.rs.util.NodeState;
import raf.rs.RPC.PauseReq;
import raf.rs.RPC.PauseRes;
import raf.rs.RPC.ResumeReq;
import raf.rs.RPC.ResumeRes;
import raf.rs.RPC.ClearMessagesReq;
import raf.rs.RPC.ClearMessagesRes;

public class NodeRPCService extends RAFTGrpc.RAFTImplBase {

    private static final Logger logger = LoggerFactory.getLogger(NodeRPCService.class);
    private boolean logEnabled = false;
    private final Node node;
    private final Object appendLogLock;


    public NodeRPCService(Node node) {
        this.node = node;
        logEnabled = System.getenv("LOG_ENBALED").equals("true");
        appendLogLock = new Object();
    }

    private void log(String msg) {
        if (logEnabled) logger.info(msg);
    }

    @Override
    public void appendEntries(AppendEntriesReq request, StreamObserver<AppendEntriesRes> responseObserver) {
        if (request.getTerm() < node.getTerm()){
            responseObserver.onNext(AppendEntriesRes.newBuilder().setSuccess(false).setTerm(node.getTerm()).build());
            responseObserver.onCompleted();
            return;
        }
        // If leader received append entries from higher term, revert to follower
        if (node.getNodeState().equals(NodeState.LEADER) && request.getTerm() > node.getTerm()) {
            synchronized (node.getStateChange()) {
                node.setNodeState(NodeState.FOLLOWER);
                node.pauseHearbeat();
                log("Recognizing higher leader term " + request.getTerm() + " > " + node.getTerm() + ". Reverting to follower state");
            }
        }
        if (node.getNodeState().equals(NodeState.CANDIDATE) && request.getTerm() >= node.getTerm()){
            synchronized (node.getStateChange()) {
                node.setNodeState(NodeState.FOLLOWER);
                log("Im candidate, but the leader is elected in term: " + request.getTerm() + ". Reverting to follower state");
            }
        }
        // Set values from leader
        node.setLeaderPort(node.addressFromNodeId(request.getLeaderId()));
        node.setCurrentTerm((int) request.getTerm());
        node.resetElectionTimeout();

        // Checking if the previous index and term matches
        synchronized (appendLogLock) {
            if (node.checkIfPrevLogMatches((int) request.getPrevLogTerm(), (int) request.getPrevLogIndex())){
                if (request.getEntryCount() > 0){
                    log("Replicating log...");
                }
                node.replicateLogEntries((int) request.getPrevLogIndex(), request.getEntryList());
                node.commitEntriesToCommitIndex(request.getLeaderCommit());
                responseObserver.onNext(AppendEntriesRes.newBuilder().setTerm(node.getTerm()).setSuccess(true).build());
                responseObserver.onCompleted();
            } else {
                responseObserver.onNext(AppendEntriesRes.newBuilder().setTerm(node.getTerm()).setSuccess(false).build());
                responseObserver.onCompleted();
            }
        }

    }

    @Override
    public synchronized void requestVote(RequestVoteReq request, StreamObserver<RequestVoteRes> responseObserver) {
        String voteNode = "CurrTerm:" + this.node.getTerm() + " Vote requested by: " + request.getCandidateId() + " " + request.getTerm() + " " + request.getLastLogTerm() + " " + request.getLastLogIndex();
        RequestVoteRes.Builder builder = RequestVoteRes.newBuilder();
        // Candidate's term is lower than the current server term, rejects the request
        if (request.getTerm() < node.getTerm()){
            log(voteNode + " Voting false. Smaller term.");
            responseObserver.onNext(builder.setTerm(node.getTerm()).setVoteGranted(false).build());
            responseObserver.onCompleted();
            return;
        }
        // Candidate with log that is not up to date doesn't get the vote
        if(!isUpToDate(request.getLastLogTerm(), request.getLastLogIndex())) {
            log(voteNode + " Voting false... Log is not up to date");
            responseObserver.onNext(builder.setTerm(node.getTerm()).setVoteGranted(false).build());
            responseObserver.onCompleted();
            return;
        }
        // If this node is leader/candidate with lower term, revert to follower
        if (request.getTerm() > node.getTerm() && !node.getNodeState().equals(NodeState.FOLLOWER)) {
            node.setNodeState(NodeState.FOLLOWER);
            node.pauseHearbeat();
        }
        // New term, reset vote
        if (request.getTerm() > node.getTerm()) {
            node.setVotedFor("");
        }
        // Update term from a message
        node.setCurrentTerm((int) request.getTerm());
        // Already voted, send false
        if(!node.getVotedFor().isEmpty()) {
            log(voteNode + " Voting false. Already voted for: " + node.getVotedFor() + " in term: " + node.getTerm());
            responseObserver.onNext(builder.setTerm(node.getTerm()).setVoteGranted(false).build());
            responseObserver.onCompleted();
            return;
        }
        // Valid request received, timeout timer restarted
        node.resetElectionTimeout();
        node.setVotedFor(node.addressFromNodeId(request.getCandidateId()));
        log(voteNode + " Voting true. Node " + node.getVotedFor() + " term: " + node.getTerm());
        responseObserver.onNext(builder.setTerm(node.getTerm()).setVoteGranted(true).build());
        responseObserver.onCompleted();
    }

    private boolean isUpToDate(long lastLogTerm, long lastLogIndex) {
        if(lastLogTerm < node.getLastEntryTerm())
            return false;
        if(lastLogTerm == node.getLastEntryTerm() && lastLogIndex < node.getLastEntryIndex())
            return false;
        return true;
    }

    @Override
    public void clientCommand(Command request, StreamObserver<ClientMessageRes> responseObserver) {
        if (!node.getNodeState().equals(NodeState.LEADER)) {
            responseObserver.onNext(ClientMessageRes.newBuilder().setInfo(node.getLeaderPort()).setSuccess(false).build());
            responseObserver.onCompleted();
            return;
        }
        if (node.isCommandsLocked()) {
            responseObserver.onNext(ClientMessageRes.newBuilder().setInfo("LOCKED").setSuccess(false).build());
            responseObserver.onCompleted();
            return;
        }
        log("Command received...");
        long commitStart = System.nanoTime();
        int entryIndex = node.addEntry(request);
        if (entryIndex == -1) {
            responseObserver.onNext(ClientMessageRes.newBuilder().setInfo("LOCKED").setSuccess(false).build());
            responseObserver.onCompleted();
            return;
        }
        if (node.waitForCommit(entryIndex, 5000)) {
            long commitMs = (System.nanoTime() - commitStart) / 1_000_000;
            log("Entry " + entryIndex + " committed successfully in " + commitMs + "ms");
            responseObserver.onNext(ClientMessageRes.newBuilder().setInfo("OK:" + commitMs).setSuccess(true).build());
        } else {
            log("Entry " + entryIndex + " commit timed out");
            responseObserver.onNext(ClientMessageRes.newBuilder().setInfo("TO").setSuccess(false).build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void clearMessages(ClearMessagesReq request, StreamObserver<ClearMessagesRes> responseObserver) {
        if (node.getNodeState().equals(NodeState.LEADER)) {
            node.clearAllMessages();
        } else {
            node.getStateMachine().clearMessages();
        }
        responseObserver.onNext(ClearMessagesRes.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getAllMessages(MessageRequest request, StreamObserver<AllMessages> responseObserver) {
        if (!this.node.getNodeState().equals(NodeState.LEADER)) {
            responseObserver.onError(io.grpc.Status.UNAVAILABLE.withDescription("not-leader").asRuntimeException());
            return;
        }
        responseObserver.onNext(AllMessages.newBuilder().addAllMessage(node.getMessages()).build());
        responseObserver.onCompleted();
    }

    @Override
    public void stop(StopReq request, StreamObserver<StopRes> responseObserver) {
        responseObserver.onNext(StopRes.newBuilder().setMessage("Node: " + node.getPort()+ " is shutting down").build());
        responseObserver.onCompleted();
        node.shutdown();
    }

    @Override
    public void pause(PauseReq request, StreamObserver<PauseRes> responseObserver) {
        node.pause();
        responseObserver.onNext(PauseRes.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void resume(ResumeReq request, StreamObserver<ResumeRes> responseObserver) {
        node.resume();
        responseObserver.onNext(ResumeRes.newBuilder().build());
        responseObserver.onCompleted();
    }
}
