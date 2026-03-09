package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

public class ClientServerSocket implements Runnable{

    private int port;

    public ClientServerSocket(int port) {
        this.port = port;
    }
    ServerSocket ss;
    Socket s;

    private volatile boolean running = true;

    @Override
    public void run() {
        try {
            ss = new ServerSocket(port);

            while (running) {
                try {
                    s = ss.accept();

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(s.getInputStream()));

                    String message = reader.readLine();
                    String[] splitMsg = message.split("\\|");
                    String type = splitMsg[0];

                    if (type.equals("0")) {
                        MainFrame.leaderPort = Integer.parseInt(splitMsg[1]);
                        continue;
                    }

                    MainFrame.getInstance().addText(splitMsg[1]);

                } catch (IOException e) {
                    if (!running) {
                        break;
                    }
                    e.printStackTrace();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopSockets() {
        running = false;
        try {
            if (ss != null) ss.close();
            if (s != null) s.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
