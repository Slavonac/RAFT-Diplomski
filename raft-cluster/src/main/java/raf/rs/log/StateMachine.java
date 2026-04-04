package raf.rs.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raf.rs.RPC.AddCommand;
import raf.rs.RPC.Command;
import raf.rs.RPC.Message;

import java.util.ArrayList;
import java.util.List;

public class StateMachine {

    private List<Message> messages;
    private static final Logger logger = LoggerFactory.getLogger(StateMachine.class);

    public StateMachine() {
        messages = new ArrayList<>();
    }

    public synchronized void applyCommand(Command command) {
        if (command.hasAddCommand()) {
            AddCommand add = command.getAddCommand();
            Message message = Message.newBuilder()
                    .setMessage(add.getMessage())
                    .setMessageId(add.getMessageId())
                    .setDeleted(false)
                    .setTimestamp(add.getTimestamp())
                    .setUser(add.getUser())
                    .build();
            messages.add(message);
        } else if (command.hasDeleteCommand()) {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).getMessageId() == command.getDeleteCommand().getMessageId()){
                    messages.set(i, messages.get(i).toBuilder().setDeleted(true).build());
                    return;
                }
            }
        } else {
            logger.warn("Unknown command received");
        }
    }

    public synchronized List<Message> getMessages () {
        return this.messages;
    }
}
