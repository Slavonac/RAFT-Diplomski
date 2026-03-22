package raf.rs.log;

import raf.rs.RPC.AddCommand;
import raf.rs.RPC.Command;
import raf.rs.util.StateMessage;

import java.util.ArrayList;
import java.util.List;

public class StateMachine {

    private List<StateMessage> messages;

    public StateMachine() {
        messages = new ArrayList<>();
    }
    public void applyCommand(Command command) {
        if (command.hasAddCommand()) {
            AddCommand add = command.getAddCommand();
            StateMessage message = new StateMessage(add.getTimestamp(), add.getUser(), add.getMessage(), add.getMessageId(), false);
            messages.add(message);
        }
    }
}
