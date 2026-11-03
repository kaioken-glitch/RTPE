package com.resiliencekit.modules.rtpe;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Sealed transaction signature. Identity = (transactionId, label); requestNo distinguishes physical requests. */
public final class Transaction {

    public enum Status { PENDING, ABSORBED, SUCCESS, FAILURE }

    private static final AtomicLong REQUEST_SEQ = new AtomicLong();

    private final String transactionId;
    private final TransactionLabel label;
    private final long amount;
    private final boolean simulateFailure;
    private final long requestNo = REQUEST_SEQ.incrementAndGet();
    private final long createdAtMs = System.currentTimeMillis();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);

    public Transaction(String transactionId, TransactionLabel label, long amount, boolean simulateFailure) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId must not be blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        this.transactionId = transactionId;
        this.label = Objects.requireNonNull(label, "label");
        this.amount = amount;
        this.simulateFailure = simulateFailure;
    }

    public String transactionId() { return transactionId; }
    public TransactionLabel label() { return label; }
    public long amount() { return amount; }
    public boolean simulateFailure() { return simulateFailure; }
    public long requestNo() { return requestNo; }
    public long ageMs() { return System.currentTimeMillis() - createdAtMs; }
    public Status status() { return status.get(); }

    public boolean transition(Status from, Status to) {
        return status.compareAndSet(from, to);
    }

    public void terminate(Status terminal) {
        status.set(terminal);
    }

    public String tag() {
        return label + "#" + transactionId + " req=" + requestNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction t)) return false;
        return transactionId.equals(t.transactionId) && label == t.label;
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, label);
    }
}
