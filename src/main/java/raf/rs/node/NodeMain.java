package raf.rs.node;

import java.io.IOException;

public class NodeMain {

    public static void main(String[] args) throws IOException {
        Node.start(Integer.parseInt(args[0]));
    }
}
