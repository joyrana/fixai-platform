package com.fixai.platform.certification.domain.evidence;

import com.fixai.platform.fixcore.FixMessageView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only, thread-safe evidence log for one scenario execution. Transport threads append; the runner thread waits
 * for new records with a bounded deadline.
 */
public final class EvidenceLog {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition appended = lock.newCondition();
    private final List<EvidenceRecord> records = new ArrayList<>();
    private final int maxRecords;
    private boolean overflowed;

    public EvidenceLog(int maxRecords) {
        this.maxRecords = maxRecords;
    }

    public EvidenceLog() {
        this(50_000);
    }

    public void message(EvidenceRecord.Direction direction, Instant at, FixMessageView view) {
        append(EvidenceRecord.Kind.MESSAGE, direction, at, view, null);
    }

    public void event(Instant at, String text) {
        append(EvidenceRecord.Kind.EVENT, null, at, null, text);
    }

    private void append(EvidenceRecord.Kind kind, EvidenceRecord.Direction direction, Instant at, FixMessageView view, String text) {
        lock.lock();
        try {
            if (records.size() >= maxRecords) {
                overflowed = true;
                return;
            }
            records.add(new EvidenceRecord(records.size(), kind, direction, at, view, text));
            appended.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public long size() {
        lock.lock();
        try {
            return records.size();
        } finally {
            lock.unlock();
        }
    }

    /** True when the evidence cap was reached; the run must then be reported as an error. */
    public boolean overflowed() {
        lock.lock();
        try {
            return overflowed;
        } finally {
            lock.unlock();
        }
    }

    public List<EvidenceRecord> snapshot() {
        lock.lock();
        try {
            return List.copyOf(records);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until the log grows beyond {@code knownSize} or the deadline passes.
     *
     * @return the current size
     */
    public long awaitGrowth(long knownSize, long deadlineNanos) throws InterruptedException {
        lock.lock();
        try {
            while (records.size() <= knownSize) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                appended.await(remaining, TimeUnit.NANOSECONDS);
            }
            return records.size();
        } finally {
            lock.unlock();
        }
    }
}
