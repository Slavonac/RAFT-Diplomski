package raf.rs.log;

import raf.rs.RPC.Command;
import raf.rs.RPC.LogEntry;

import java.util.ArrayList;
import java.util.List;

public class Log {

    private List<LogEntry> log;

    public Log() {
        this.log = new ArrayList<>();
    }
    public void add(LogEntry entry) {
        this.log.add(entry);
    }
    public int getIndexForNextEntry() {
        if (this.log.isEmpty()) return 1;
        return (int)this.log.getLast().getIndex() + 1;
    }

    public int getPreviousLogIndex(int nextIndex) {
        if (nextIndex == 1)
            return 0;
        return (int)log.get(nextIndex - 2).getIndex();
    }
    public int getPreviousLogTerm(int nextIndex) {
        if (nextIndex == 1)
            return 0;
        return (int)log.get(nextIndex - 2).getTerm();
    }
    public int getLastEntryIndex() { return log.isEmpty() ? 0 : (int) log.getLast().getIndex(); }
    public int getLastEntryTerm() { return log.isEmpty() ? 0 : (int) log.getLast().getTerm(); }

    public boolean checkIfPrevLogMatches(int term, int index) {
        if (log.isEmpty()) return true;
        return log.getLast().getIndex() == index && log.getLast().getTerm() == term;
    }
    public int getSize() { return this.log.size(); }
    public int getTerm(int n) { return (int)this.log.get(n - 1).getTerm(); }
    public Command getCommand(int index) { return log.get(index - 1).getCommand(); }
}
