package raf.rs.node;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raf.rs.RPC.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketListener implements Runnable {

    private final int port;
    private final Node node;

    private ServerSocket ss;
    private ExecutorService executorService;
    private Gson gson;

    private static Logger logger = LoggerFactory.getLogger(SocketListener.class);

    public SocketListener(int port, Node node) {
        this.port = port;
        this.node = node;
        this.gson = new Gson();
    }

    @Override
    public void run() {
        ss = null;
        executorService = Executors.newCachedThreadPool();
        try {
            ss = new ServerSocket(this.port);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        while (true) {
            try {
                Socket s = ss.accept();
                final Socket finalSocket = s;
                executorService.submit(() -> handleSocket(finalSocket));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void handleSocket(Socket s) {

        try (Socket socket = s; BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            String message;

            while ((message = br.readLine()) != null) {
                JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
                MessageType type = MessageType.valueOf(obj.get("messageType").getAsString());

                switch (type) {
                    case MessageType.STOP:
                        logger.info("Stopping node...");
                        node.stopNode();
                        ss.close();
                        executorService.shutdown();
                        break;
                    case MessageType.REQUEST_VOTE:
                        RequestVote req = gson.fromJson(obj, RequestVote.class);
                        logger.info("Requested vote... " + req.getValues());
                        node.requestVoteRPC(req);
                        break;
                    case MessageType.REQUEST_VOTE_RESPONSE:
                        RequestVoteResponse res = gson.fromJson(obj, RequestVoteResponse.class);
                        logger.info("Node: " + res.getNodePort() + " voted for term: " + res.getTerm() + " vote granted: " + res.isVoteGranted());
                        node.voteResult(res.isVoteGranted(), res.getTerm());
                        break;
                    case MessageType.APPEND_ENTRIES:
                        AppendEntries entries = gson.fromJson(obj, AppendEntries.class);
                        node.appendEntries(entries);
                        break;
                    case MessageType.CLIENT_ADD_MESSAGE:
                        ClientAddMessage clientAddMessage = gson.fromJson(obj, ClientAddMessage.class);
                        node.addClientRequest(clientAddMessage);
                        break;
                    case MessageType.CLIENT_DELETE_MESSAGE:
                        ClientDeleteMessage clientDeleteMessage = gson.fromJson(obj, ClientDeleteMessage.class);
                        break;
                        // Test purpose
                    case MessageType.LEADER_PORT:
                        LeaderPort lp = gson.fromJson(obj, LeaderPort.class);
                        logger.info(lp.getLeaderPort() + " Asked where is leader port, sending: " + node.getLeaderPort());
                        node.sendLeaderPort(lp.getLeaderPort());
                        break;
                    default:
                        logger.info("UNKNOWN MESSAGE: " + message);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
