package rtpe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, lock-free ring of diagnostic events consumed by the cockpit. */
public final class EventLog {

    public record Entry(long seq, long ts, String level, String message) {}

    private static final int CAPACITY = 300;

    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong seq = new AtomicLong();

    public void log(String level, String fmt, Object... args) {
        Entry e = new Entry(seq.incrementAndGet(), System.currentTimeMillis(), level, String.format(fmt, args));
        entries.addLast(e);
        System.out.printf("%tT.%<tL %-5s %s%n", e.ts(), level, e.message());
        if (size.incrementAndGet() > CAPACITY) {
            entries.pollFirst();
            size.decrementAndGet();
        }
    }

    public List<Entry> since(long afterSeq) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq() > afterSeq) out.add(e);
        }
        return out;
    }

    public long lastSeq() {
        return seq.get();
    }

    public void clear() {
        entries.clear();
        size.set(0);
    }
}
