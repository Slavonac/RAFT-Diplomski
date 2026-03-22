package raf.rs;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import raf.rs.RPC.RAFTGrpc;
import raf.rs.RPC.StopReq;

import java.io.*;
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
                    ManagedChannel mc = ManagedChannelBuilder.forAddress("localhost", Integer.parseInt(props.getProperty("node" + i))).usePlaintext().build();
                    RAFTGrpc.RAFTBlockingStub stub = RAFTGrpc.newBlockingStub(mc);
                    System.out.println(stub.stop(StopReq.newBuilder().build()).getMessage());
                    mc.shutdownNow();
                }
                break;
            }
        }
    }
}