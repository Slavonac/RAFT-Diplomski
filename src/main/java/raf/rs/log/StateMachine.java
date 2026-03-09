package raf.rs.log;

import raf.rs.util.ClientMessage;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class StateMachine {

    private List<ClientMessage> messages;

    public StateMachine() {
        messages = new ArrayList<>();
    }
}
