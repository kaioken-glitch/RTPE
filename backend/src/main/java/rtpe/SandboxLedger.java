package rtpe;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Simulated credit engine: the "operational criteria" evaluated when a transaction window expires. */
public final class SandboxLedger {

    public static final long INITIAL_BALANCE_CENTS = 100_000;
    public static final int STOCK_UNITS = 50;

    private final EventLog log;
    private final AtomicLong balanceCents = new AtomicLong(INITIAL_BALANCE_CENTS);
    private final AtomicInteger lockedUnits = new AtomicInteger();
    private final LongAdder debits = new LongAdder();
    private final LongAdder credits = new LongAdder();
    private final LongAdder locks = new LongAdder();

    public SandboxLedger(EventLog log) {
        this.log = log;
    }

    public boolean execute(Transaction tx) {
        if (tx.simulateFailure()) {
            log.log("WARN", "[%s] ledger rejected: injected downstream failure", tx.tag());
            return false;
        }
        return switch (tx.label()) {
            case PAYMENT -> debit(tx);
            case REFUND -> {
                long after = balanceCents.addAndGet(tx.amount());
                credits.increment();
                log.log("INFO", "[%s] ledger CREDIT +%d -> balance=%d", tx.tag(), tx.amount(), after);
                yield true;
            }
            case INVENTORY_LOCK -> lock(tx);
        };
    }

    private boolean debit(Transaction tx) {
        for (;;) {
            long current = balanceCents.get();
            if (current < tx.amount()) {
                log.log("WARN", "[%s] ledger rejected: insufficient funds (%d < %d)", tx.tag(), current, tx.amount());
                return false;
            }
            if (balanceCents.compareAndSet(current, current - tx.amount())) {
                debits.increment();
                log.log("INFO", "[%s] ledger DEBIT -%d -> balance=%d", tx.tag(), tx.amount(), current - tx.amount());
                return true;
            }
        }
    }

    private boolean lock(Transaction tx) {
        int units = (int) tx.amount();
        for (;;) {
            int current = lockedUnits.get();
            if (current + units > STOCK_UNITS) {
                log.log("WARN", "[%s] ledger rejected: only %d units free", tx.tag(), STOCK_UNITS - current);
                return false;
            }
            if (lockedUnits.compareAndSet(current, current + units)) {
                locks.increment();
                log.log("INFO", "[%s] ledger LOCK %d units -> locked=%d", tx.tag(), units, current + units);
                return true;
            }
        }
    }

    public void reset() {
        balanceCents.set(INITIAL_BALANCE_CENTS);
        lockedUnits.set(0);
        debits.reset();
        credits.reset();
        locks.reset();
    }

    public long balanceCents() { return balanceCents.get(); }
    public int lockedUnits() { return lockedUnits.get(); }
    public long debits() { return debits.sum(); }
    public long credits() { return credits.sum(); }
    public long locks() { return locks.sum(); }
}
