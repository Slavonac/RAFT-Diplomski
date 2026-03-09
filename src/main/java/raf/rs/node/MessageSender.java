package raf.rs.node;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raf.rs.RPC.AppendEntries;
import raf.rs.RPC.RequestVote;
import raf.rs.RPC.RequestVoteResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessageSender {

    private List<Integer> ports;
    private ExecutorService executorService;
    private Map<Integer, PrintWriter> connections;
    private List<Socket> activeSockets;
    private Node node;
    private Gson gson;

    private static Logger logger = LoggerFactory.getLogger(MessageSender.class);


    public MessageSender(List<Integer> ports, Node node) {
        this.ports = ports;
        this.executorService = Executors.newFixedThreadPool(4);
        this.connections = new HashMap<>();
        this.activeSockets = new ArrayList<>();
        this.node = node;
        this.gson = new Gson();
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        for (Integer port : ports) {
            try {
                Socket s = new Socket("localhost", port);
                this.connections.put(port, new PrintWriter(s.getOutputStream(), true));
                this.activeSockets.add(s);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void appendEntries(int term, int leaderPort) {
        AppendEntries appendEntries = new AppendEntries(term, leaderPort);
        String s = gson.toJson(appendEntries);
        for(Integer port : connections.keySet()) {
            this.executorService.submit(() -> connections.get(port).println(s));
        }
    }

    public void requestVote(){
        RequestVote requestVote = new RequestVote(node.getTerm(), node.getPort(), 0, 0);
        String s = gson.toJson(requestVote);
        for (Integer port : connections.keySet()) {
            logger.info("Reqesting vote for node: " + port);
            this.executorService.submit(() -> connections.get(port).println(s));
        }
    }

    public void submitVote(Integer port, int term, boolean voteGranted) {
        PrintWriter pw = connections.get(port);
        RequestVoteResponse message = new RequestVoteResponse(term, voteGranted);
        message.setNodePort(node.getPort());
        String response = gson.toJson(message);
        logger.info("Submitting vote...");
        pw.println(response);
    }

    public void giveLeaderAddress(int port) throws IOException {
        Socket s;
        s = new Socket("localhost", port);
        PrintWriter printWriter = new PrintWriter(s.getOutputStream(), true);
        printWriter.println("0|" + this.node.getLeaderPort());
        s.close();
    }

    public void closeExec() {
        this.executorService.close();
    }

    public void closeActiveSockets() {
        for (Socket s : activeSockets) {
            try {
                s.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        for (PrintWriter pw : connections.values()){
            pw.close();
        }
    }

}
