package raf.rs.node;

import java.io.IOException;

public class NodeMain {

    public static void main(String[] args) throws IOException {
        Node node = new Node(Integer.parseInt(args[0]));
    }
}
