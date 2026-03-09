package raf.rs;

import com.google.gson.Gson;
import raf.rs.RPC.Stop;

import java.io.*;
import java.net.Socket;
import java.util.Properties;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws IOException {

        String classpath = System.getProperty("java.class.path");
        Properties props = new Properties();
        try (InputStream in = new FileInputStream("io/config.properties")) {
            props.load(in);
        }
        int numOfNodes = Integer.parseInt(props.getProperty("nodes"));

        for (int i = 0; i < numOfNodes; i++) {

            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "-cp", classpath,
                    "raf.rs.node.NodeMain",
                    String.valueOf(i)
            );


            pb.redirectInput(new File("io/input/input" + i + ".txt"));
            pb.redirectOutput(new File("io/output/output" + i + ".txt"));
            pb.redirectError(new File("io/error/error" + i + ".txt"));
            pb.start();
        }
        boolean run = true;
        while (run) {
            Scanner sc = new Scanner(System.in);
            String line = sc.nextLine();
            if (line.equals("stop")){
                for (int i = 0; i < numOfNodes; i++){
                    try (Socket socket = new Socket("localhost", Integer.parseInt(props.getProperty("node" + i)));PrintWriter out = new PrintWriter(socket.getOutputStream(), true)){
                        Gson gson = new Gson();
                        out.println(gson.toJson(new Stop()));
                    } catch (IOException e) {
                        System.out.println("Node on port already down");
                    }
                }
                break;
            }
        }
    }
}