package raf.rs;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import raf.rs.RPC.RAFTGrpc;
import raf.rs.RPC.StopReq;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {

        String classpath = System.getProperty("java.class.path");
        Properties props = new Properties();
        try (InputStream in = new FileInputStream("io/config.properties")) {
            props.load(in);
        }
        int numOfNodes = Integer.parseInt(props.getProperty("nodes"));
        List<Process> processes = new ArrayList<>();
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
            processes.add(pb.start());
        }


        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (int i = 0; i < numOfNodes; i++) {
                try {
                    ManagedChannel mc = ManagedChannelBuilder
                            .forAddress(props.getProperty("node" + i).split(":")[0], Integer.parseInt(props.getProperty("node" + i).split(":")[1]))
                            .usePlaintext().build();
                    RAFTGrpc.newBlockingStub(mc).stop(StopReq.newBuilder().build());
                    mc.shutdownNow();
                } catch (Exception ignored) {}
            }
        }));

        Thread.currentThread().join();
    }
}
