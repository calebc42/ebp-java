// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.store;

/** Pairing-local counters and time state that must survive process death:
 * SPEC 15.1 queue_seq, SPEC 13.1 first-seen ordinals, the SPEC 15.2
 * effective-clock high-water mark, and the SPEC 10.4 disconnection time. */
public final class Counters {
    public final long nextQueueSeq;
    public final long nextSurfaceOrdinal;
    public final long clockHighWaterMs;
    public final Long readyDisconnectedAtMs; // null when never disconnected from READY

    public Counters(long nextQueueSeq, long nextSurfaceOrdinal, long clockHighWaterMs,
                    Long readyDisconnectedAtMs) {
        if (nextQueueSeq < 1 || nextSurfaceOrdinal < 1 || clockHighWaterMs < 0)
            throw new IllegalArgumentException("counters");
        this.nextQueueSeq = nextQueueSeq;
        this.nextSurfaceOrdinal = nextSurfaceOrdinal;
        this.clockHighWaterMs = clockHighWaterMs;
        this.readyDisconnectedAtMs = readyDisconnectedAtMs;
    }

    public static Counters initial() { return new Counters(1, 1, 0, null); }

    public Counters withNextQueueSeq(long v) { return new Counters(v, nextSurfaceOrdinal, clockHighWaterMs, readyDisconnectedAtMs); }
    public Counters withNextSurfaceOrdinal(long v) { return new Counters(nextQueueSeq, v, clockHighWaterMs, readyDisconnectedAtMs); }
    public Counters withClockHighWaterMs(long v) { return new Counters(nextQueueSeq, nextSurfaceOrdinal, v, readyDisconnectedAtMs); }
    public Counters withReadyDisconnectedAtMs(Long v) { return new Counters(nextQueueSeq, nextSurfaceOrdinal, clockHighWaterMs, v); }
}
