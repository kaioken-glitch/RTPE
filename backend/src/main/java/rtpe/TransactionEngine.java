package rtpe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Debouncing, self-healing transaction queue.
 *
 * Concurrency contract: every state change for a given (id, label) key happens inside
 * {@code activeTransactions.compute(key, ..)}, so absorb / settle / promote are serialized per key
 * without a global lock. {@code absorbedStore} is only mutated from within those critical sections.
 */
public final class TransactionEngine implements AutoCloseable {

    public enum SubmitOutcome { ACCEPTED, ABSORBED }

    @FunctionalInterface
    public interface Handler {
        boolean execute(Transaction tx);
    }

    public static final class Stats {
        public final LongAdder submitted = new LongAdder();
        public final LongAdder accepted = new LongAdder();
        public final LongAdder absorbed = new LongAdder();
        public final LongAdder succeeded = new LongAdder();
        public final LongAdder failed = new LongAdder();
        public final LongAdder recovered = new LongAdder();
        public final LongAdder discarded = new LongAdder();

        void reset() {
            for (LongAdder a : new LongAdder[] {submitted, accepted, absorbed, succeeded, failed, recovered, discarded}) {
                a.reset();
            }
        }
    }

    public record ActiveView(String id, String label, long windowMs, long remainingMs,
                             int absorbed, int attempt, long requestNo) {}

    public record Snapshot(List<ActiveView> active, int absorbedKeys, int absorbedItems) {}

    private static final long MAX_ABSORB_AGE_MS = 30_000;

    private static final class Slot {
        final Transaction tx;
        final long startNanos = System.nanoTime();
        final long windowMs;
        final int attempt;
        volatile ScheduledFuture<?> timer;

        Slot(Transaction tx, int attempt) {
            this.tx = tx;
            this.attempt = attempt;
            this.windowMs = tx.label().windowMs();
        }
    }

    private final Map<Transaction, Slot> activeTransactions = new ConcurrentHashMap<>();
    private final Map<Transaction, ConcurrentLinkedDeque<Transaction>> absorbedStore = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler;
    private final Handler handler;
    private final EventLog log;
    private final Stats stats = new Stats();

    public TransactionEngine(Handler handler, EventLog log) {
        this.handler = handler;
        this.log = log;
        this.scheduler = new ScheduledThreadPoolExecutor(4, r -> {
            Thread t = new Thread(r, "rtpe-window");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    public Stats stats() {
        return stats;
    }

    /** Invariant 2: atomic debounce. First request owns the window; matching requests are absorbed. */
    public SubmitOutcome submit(Transaction tx) {
        stats.submitted.increment();
        SubmitOutcome[] outcome = new SubmitOutcome[1];

        activeTransactions.compute(tx, (key, existing) -> {
            if (existing == null) {
                outcome[0] = SubmitOutcome.ACCEPTED;
                stats.accepted.increment();
                return open(tx, 0);
            }
            outcome[0] = SubmitOutcome.ABSORBED;
            stats.absorbed.increment();
            tx.transition(Transaction.Status.PENDING, Transaction.Status.ABSORBED);
            ConcurrentLinkedDeque<Transaction> buffer =
                    absorbedStore.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
            buffer.addLast(tx);
            long remaining = Math.max(0, existing.windowMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - existing.startNanos));
            log.log("WARN", "[%s] ABSORBED into secondary buffer (depth=%d, window remaining %dms, owner req=%d)",
                    tx.tag(), buffer.size(), remaining, existing.tx.requestNo());
            return existing;
        });
        return outcome[0];
    }

    /** Invariant 3: detached timer per window. */
    private Slot open(Transaction tx, int attempt) {
        Slot slot = new Slot(tx, attempt);
        slot.timer = scheduler.schedule(() -> expire(slot), slot.windowMs, TimeUnit.MILLISECONDS);
        log.log("INFO", "[%s] WINDOW OPEN %dms%s", tx.tag(), slot.windowMs,
                attempt > 0 ? " (attempt " + (attempt + 1) + ", promoted from absorbedStore)" : "");
        return slot;
    }

    private void expire(Slot slot) {
        boolean ok;
        try {
            ok = handler.execute(slot.tx);
        } catch (RuntimeException e) {
            ok = false;
            log.log("ERROR", "[%s] handler threw %s", slot.tx.tag(), e);
        }
        settle(slot, ok);
    }

    private void settle(Slot slot, boolean ok) {
        Transaction tx = slot.tx;
        tx.terminate(ok ? Transaction.Status.SUCCESS : Transaction.Status.FAILURE);
        if (ok) stats.succeeded.increment(); else stats.failed.increment();

        activeTransactions.compute(tx, (key, current) -> {
            if (current != slot) {
                log.log("WARN", "[%s] stale settle ignored (engine reset)", tx.tag());
                return current;
            }
            ConcurrentLinkedDeque<Transaction> buffer = absorbedStore.get(key);

            if (ok) {
                // Invariant 5: terminal success evicts every footprint, including buffered duplicates.
                int dropped = buffer == null ? 0 : buffer.size();
                absorbedStore.remove(key);
                stats.discarded.add(dropped);
                log.log("INFO", "[%s] SUCCESS -> evicted active slot and %d absorbed duplicate(s)", tx.tag(), dropped);
                return null;
            }

            // Invariant 4: circuit failover. Pop the next valid duplicate and re-open a window for it.
            log.log("ERROR", "[%s] FAILURE -> interrogating absorbedStore (%d candidate(s))",
                    tx.tag(), buffer == null ? 0 : buffer.size());
            Transaction next = null;
            while (buffer != null && (next = buffer.pollFirst()) != null) {
                if (revalidate(next)) break;
                stats.discarded.increment();
                log.log("WARN", "[%s] candidate failed re-validation, discarded", next.tag());
                next = null;
            }
            if (buffer != null && buffer.isEmpty()) absorbedStore.remove(key);

            if (next == null) {
                log.log("ERROR", "[%s] no fallback available -> terminal FAILURE, state evicted", tx.tag());
                return null;
            }
            stats.recovered.increment();
            next.transition(Transaction.Status.ABSORBED, Transaction.Status.PENDING);
            log.log("INFO", "[%s] RECOVERED via req=%d -> re-submitting to engine", tx.tag(), next.requestNo());
            return open(next, slot.attempt + 1);
        });
    }

    private static boolean revalidate(Transaction candidate) {
        return candidate.status() == Transaction.Status.ABSORBED
                && candidate.amount() > 0
                && candidate.ageMs() <= MAX_ABSORB_AGE_MS;
    }

    public Snapshot snapshot() {
        List<ActiveView> views = new ArrayList<>();
        long now = System.nanoTime();
        for (Map.Entry<Transaction, Slot> e : activeTransactions.entrySet()) {
            Slot s = e.getValue();
            ConcurrentLinkedDeque<Transaction> buffer = absorbedStore.get(e.getKey());
            long remaining = Math.max(0, s.windowMs - TimeUnit.NANOSECONDS.toMillis(now - s.startNanos));
            views.add(new ActiveView(s.tx.transactionId(), s.tx.label().name(), s.windowMs, remaining,
                    buffer == null ? 0 : buffer.size(), s.attempt, s.tx.requestNo()));
        }
        views.sort((a, b) -> Long.compare(a.requestNo(), b.requestNo()));
        int items = 0;
        for (ConcurrentLinkedDeque<Transaction> d : absorbedStore.values()) items += d.size();
        return new Snapshot(views, absorbedStore.size(), items);
    }

    public void reset() {
        for (Slot s : activeTransactions.values()) {
            ScheduledFuture<?> t = s.timer;
            if (t != null) t.cancel(false);
        }
        activeTransactions.clear();
        absorbedStore.clear();
        stats.reset();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
