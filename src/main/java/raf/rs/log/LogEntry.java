package raf.rs.log;

import raf.rs.log.commands.StateCommand;

public class LogEntry {

    private int term;
    private int index;
    private StateCommand command;

    public LogEntry(int term, StateCommand command) {
        this.term = term;
        this.command = command;
    }
}
