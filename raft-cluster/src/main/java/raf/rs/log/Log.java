package raf.rs.log;

import raf.rs.RPC.Command;
import raf.rs.RPC.LogEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Log {

    private List<LogEntry> log;

    public Log() {
        this.log = new ArrayList<>();
    }
    public void add(List<LogEntry> entry) {
        this.log.addAll(entry);
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

    public boolean checkIfPrevLogMatches(int prevLogTerm, int prevLogIndex) {
        if (prevLogIndex == 0) return true;
        if (prevLogIndex > log.size()) return false;
        LogEntry entry = log.get(prevLogIndex - 1);
        return entry.getIndex() == prevLogIndex && entry.getTerm() == prevLogTerm;
    }
    public int getSize() { return this.log.size(); }
    public int getTerm(int n) { return (int)this.log.get(n - 1).getTerm(); }
    public Command getCommand(int index) { return log.get(index - 1).getCommand(); }

    public List<LogEntry> getEntriesFrom(int fromIndex) {
        if (fromIndex < 1 || fromIndex > log.size()) return Collections.emptyList();
        return new ArrayList<>(log.subList(fromIndex - 1, log.size()));
    }

    public void truncateFrom(int fromIndex) {
        if (fromIndex < 1 || fromIndex > log.size()) return;
        log.subList(fromIndex - 1, log.size()).clear();
    }

    public int getTermAtIndex(int index) {
        if (index < 1 || index > log.size()) return 0;
        return (int) log.get(index - 1).getTerm();
    }
}
