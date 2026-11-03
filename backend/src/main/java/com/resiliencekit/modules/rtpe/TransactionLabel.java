package com.resiliencekit.modules.rtpe;

/** Per-class operational profile. The debounce window is structural, never a runtime string. */
public enum TransactionLabel {
    PAYMENT(2_000),
    REFUND(3_000),
    INVENTORY_LOCK(1_500);

    private final long windowMs;

    TransactionLabel(long windowMs) {
        this.windowMs = windowMs;
    }

    public long windowMs() {
        return windowMs;
    }
}
