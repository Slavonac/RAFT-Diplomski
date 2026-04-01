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

public class NodeRPCService extends RAFTGrpc.RAFTImplBase {

    private static Logger logger = LoggerFactory.getLogger(NodeRPCService.class);
    private Node node;
    private final Object appendLogLock;


    public NodeRPCService(Node node) {
        this.node = node;
        appendLogLock = new Object();
    }

    @Override
    public void appendEntries(AppendEntriesReq request, StreamObserver<AppendEntriesRes> responseObserver) {
        // If candidate or leader received append entries from higher term, revert to follower
        if (node.getNodeState().equals(NodeState.LEADER) && node.getNodeState().equals(NodeState.CANDIDATE) && request.getTerm() > node.getTerm()) {
            synchronized (node.getStateChange()) {
                node.setNodeState(NodeState.FOLLOWER);
                node.pauseHearbeat();
                logger.info("Recognizing higher leader term " + request.getTerm() + " > " + node.getTerm() + ". Reverting to follower state");
            }
        }
        // Set values from leader
        node.setLeaderPort(node.addressFromNodeId(request.getLeaderId()));
        node.setCurrentTerm((int) request.getTerm());
        node.resetElectionTimeout();
        // Get entry info, check if node has the same previous entry as the leader, respond accordingly
        LogEntry entry = request.getEntry();
        long prevIndex = request.getPrevLogIndex();
        long prevTerm = request.getPrevLogTerm();

        synchronized (appendLogLock) {
            if (node.checkIfPrevLogMatches((int) prevTerm, (int) prevIndex)){
                if (request.hasEntry()){
                    logger.info("Replicating log...");
                    node.addReplicatedLog(entry);
                }
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
            logger.info(voteNode + " Voting false. Smaller term.");
            responseObserver.onNext(builder.setTerm(node.getTerm()).setVoteGranted(false).build());
            responseObserver.onCompleted();
            return;
        }
        // Candidate with log that is not up to date doesn't get the vote
        if(!isUpToDate(request.getLastLogTerm(), request.getLastLogIndex())) {
            responseObserver.onNext(builder.setTerm(node.getTerm()).setVoteGranted(false).build());
            responseObserver.onCompleted();
            return;
        }
        // If this node is leader/candidate with lower term, revert to follower
        if (request.getTerm() > node.getTerm() && !node.getNodeState().equals(NodeState.FOLLOWER)) {
            node.setNodeState(NodeState.FOLLOWER);
            node.pauseHearbeat();
        }
        // Valid request received, timeout timer restarted
        node.resetElectionTimeout();
        // New term, reset vote
        if (request.getTerm() != node.getTerm()) {
            node.setVotedFor("");
        }
        // Update term from a message
        node.setCurrentTerm((int) request.getTerm());
        // Already voted, send false
        if(!node.getVotedFor().isEmpty()) {
            logger.info(voteNode + " Voting false. Already voted for: " + node.getVotedFor() + " in term: " + node.getTerm());
            responseObserver.onNext(builder.setTerm(node.getTerm()).setVoteGranted(false).build());
            responseObserver.onCompleted();
            return;
        }
        node.setVotedFor(node.addressFromNodeId(request.getCandidateId()));
        logger.info(voteNode + " Voting true. Node " + node.getVotedFor() + " term: " + node.getTerm());
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
        logger.info("Command received...");
        node.addEntry(request);
        responseObserver.onNext(ClientMessageRes.newBuilder().setInfo("zz").setSuccess(true).build());
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
