package raf.rs.log.commands;

import raf.rs.RPC.ClientAddMessage;

public class AddMessageCommand implements StateCommand{

    private ClientAddMessage clientAddMessage;

    public AddMessageCommand(ClientAddMessage clientAddMessage) {
        this.clientAddMessage = clientAddMessage;
    }

    @Override
    public void doCommand() {

    }
}
