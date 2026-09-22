// SPDX-License-Identifier: GPL-3.0-or-later
// The SPEC 15 durable event queue: admission with per-pairing queue_seq
// FIFO, dedupe as queue compaction, rollback-proof expiry through the
// durable effective-clock mark, EventId non-reuse, and the capacity limits —
// every mutation one transaction of the CompanionStore.
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.store.CompanionStore;
import com.calebc42.ebp.companion.store.Counters;
import com.calebc42.ebp.companion.store.IssuedEventId;
import com.calebc42.ebp.companion.store.OutboxRecord;
import com.calebc42.ebp.companion.store.Snapshot;
import com.calebc42.ebp.companion.store.StoreException;
import com.calebc42.ebp.companion.store.WriteTx;

import java.util.ArrayList;
import java.util.List;

public final class DurableQueue {

    /** SPEC 14.4: an EventId is not reused within 604800 seconds. */
    public static final long EVENT_ID_RETENTION_MS = 604800000L;

    /** SPEC 15.2: the stored mark never leads the wall clock by more. */
    private static final long MAX_CLOCK_LEAD_MS = 604800000L;
    /** SPEC 15.2: a forward step is a claim until it persists this long. */
    private static final long FORWARD_CLAIM_MS = 60000L;

    public enum AdmitStatus { ADMITTED, QUEUE_FULL, STORAGE_FAILED, EVENT_ID_REUSED }

    public static final class AdmitResult {
        public final AdmitStatus status;
        public final OutboxRecord record; // when ADMITTED
        AdmitResult(AdmitStatus status, OutboxRecord record) { this.status = status; this.record = record; }
    }

    public enum DeliveryKind { READY, EMPTY, BARRIER_HELD }

    /** SPEC 15.3: the atomic outcome of selecting the next event to deliver. */
    public static final class Delivery {
        public final DeliveryKind kind;
        public final OutboxRecord record; // when READY
        Delivery(DeliveryKind kind, OutboxRecord record) { this.kind = kind; this.record = record; }
    }

    private final CompanionStore store;
    private final String pairingId;
    private final long maxEvents;
    private final long maxBytes;
    private final Clock clock;
    private final Object monitor = new Object();

    private List<OutboxRecord> records;   // ascending queue_seq, mirrors the store
    private long nextSeq;
    private long highWater;
    // SPEC 15.2 forward-step corroboration (runtime; a restart re-observes).
    private long lastWallMs;
    private long lastMonoMs;
    private long claimWallMs = -1;
    private long claimMonoStart = -1;
    /** queue_seq of the record currently in flight, runtime-only: after a
     * process death nothing is in flight (SPEC 15.3). */
    private Long inFlightSeq = null;
    /** Expiry deletions not yet reported in a replay summary (SPEC 15.2). */
    private int pendingExpired = 0;

    public DurableQueue(CompanionStore store, String pairingId, long maxEvents, long maxBytes, Clock clock)
        throws StoreException {
        this.store = store;
        this.pairingId = pairingId;
        this.maxEvents = maxEvents;
        this.maxBytes = maxBytes;
        this.clock = clock;
        Snapshot s = store.load(pairingId);
        records = new ArrayList<OutboxRecord>(s.outbox);
        nextSeq = s.counters.nextQueueSeq;
        highWater = s.counters.clockHighWaterMs;
        lastWallMs = clock.wallMs();
        lastMonoMs = clock.monotonicMs();
    }

    // ------------------------------------------------------- clock (15.2)

    /** SPEC 15.2: the effective wall clock — never behind the durable mark,
     * and never ahead of what monotonic elapsed time corroborates.  Pure
     * (does not persist); the mark advances at the next durable write. */
    public long effectiveNow() {
        synchronized (monitor) { return effectiveNowLocked(); }
    }

    private long effectiveNowLocked() {
        long wall = clock.wallMs();
        long mono = clock.monotonicMs();
        long corroborated = lastWallMs + Math.max(0, mono - lastMonoMs);
        long candidate;
        if (wall <= corroborated + 1000) {
            // Ordinary passage of time (or a backward step, which the mark
            // below simply ignores).
            candidate = Math.max(wall, corroborated);
            claimWallMs = -1;
        } else {
            // A forward step beyond elapsed time is a claim; honor it only
            // after it has persisted for 60 s of monotonic time.
            if (claimWallMs < 0 || Math.abs((wall - mono) - claimWallMs) > 1000) {
                claimWallMs = wall - mono;
                claimMonoStart = mono;
            }
            candidate = (mono - claimMonoStart >= FORWARD_CLAIM_MS) ? wall : corroborated;
        }
        lastWallMs = candidate;
        lastMonoMs = mono;
        long effective = Math.max(candidate, highWater);
        // SPEC 15.2: the stored mark never leads the current wall clock by
        // more than 604800 s; clamp on observing such a lead.
        if (highWater > wall + MAX_CLOCK_LEAD_MS) {
            highWater = wall + MAX_CLOCK_LEAD_MS;
            effective = Math.max(candidate, highWater);
        }
        return effective;
    }

    // ------------------------------------------------------------- reads

    public int count() { synchronized (monitor) { return records.size(); } }

    public boolean hasInFlight() { synchronized (monitor) { return inFlightSeq != null; } }

    public Long inFlightSeq() { synchronized (monitor) { return inFlightSeq; } }

    /** The next queue_seq to be assigned — the session-barrier boundary
     * (SPEC 10.3/15.3): records at or above a snapshot of this value are
     * newly generated relative to that snapshot. */
    public long boundarySeq() { synchronized (monitor) { return nextSeq; } }

    public List<OutboxRecord> events() {
        synchronized (monitor) { return new ArrayList<OutboxRecord>(records); }
    }

    public int takeExpiredCount() {
        synchronized (monitor) { int n = pendingExpired; pendingExpired = 0; return n; }
    }

    // --------------------------------------------------- admission (15.1)

    /**
     * SPEC 15.1/15.2/15.4: one durable transaction covering expiry sweep,
     * dedupe compaction, EventId non-reuse, counter advance, byte and count
     * limits, and record insertion.  EVENT is the complete event.action
     * params carrying event_id, occurred_at_ms, and queued_at_ms.
     */
    public AdmitResult admit(final Json.Obj event, final String policy, final String dedupe, final long ttlSeconds) {
        synchronized (monitor) {
            final long now = effectiveNowLocked();
            final String eventId = event.string("event_id");
            final Long occurred = event.integer("occurred_at_ms");
            final Long queued = event.integer("queued_at_ms");
            if (eventId == null || occurred == null || queued == null)
                throw new IllegalArgumentException("event lacks its durable identity");
            final long expiresAt = occurred.longValue() + ttlSeconds * 1000L;
            final byte[] serialized = Json.writeUtf8(event);
            final long accounted = serialized.length + 256L; // metadata overhead, conservatively
            final Long protectedSeq = inFlightSeq;
            Failpoints.hit(Failpoints.OUTBOX_BEFORE_COMMIT);
            final List<OutboxRecord> mirror = new ArrayList<OutboxRecord>();
            final long[] nextSeqOut = new long[1];
            AdmitResult result;
            try {
                result = store.write(pairingId, new CompanionStore.WriteBlock<AdmitResult>() {
                    @Override public AdmitResult apply(WriteTx tx) throws StoreException {
                        Counters c = tx.counters();
                        long clockMark = Math.max(c.clockHighWaterMs, now);
                        // SPEC 14.4: an EventId MUST NOT be reused within retention.
                        for (IssuedEventId issued : tx.issuedEventIds()) {
                            if (issued.eventId.equals(eventId)) {
                                if (clockMark < issued.reusableAfterMs)
                                    return new AdmitResult(AdmitStatus.EVENT_ID_REUSED, null);
                                tx.deleteIssuedEventId(eventId);
                            } else if (issued.reusableAfterMs <= clockMark) {
                                tx.deleteIssuedEventId(issued.eventId); // prune
                            }
                        }
                        List<OutboxRecord> kept = new ArrayList<OutboxRecord>();
                        long bytes = 0;
                        for (OutboxRecord r : tx.outbox()) {
                            boolean protectedRecord = protectedSeq != null && protectedSeq.longValue() == r.queueSeq;
                            // SPEC 15.2: expired records are deleted before delivery.
                            if (!protectedRecord && r.expiresAtMs <= clockMark) {
                                tx.deleteOutbox(r.queueSeq);
                                pendingExpiredIncrement();
                                continue;
                            }
                            // SPEC 15.2: replace older queued, non-in-flight, same-key events.
                            if (dedupe != null && !protectedRecord && dedupe.equals(r.dedupe)) {
                                tx.deleteOutbox(r.queueSeq);
                                continue;
                            }
                            kept.add(r);
                            bytes += r.accountedBytes;
                        }
                        // SPEC 15.4: both capacity limits, atomically at admission.
                        if (kept.size() >= maxEvents || bytes + accounted > maxBytes) {
                            tx.putCounters(c.withClockHighWaterMs(clockMark));
                            mirror.addAll(kept);
                            nextSeqOut[0] = c.nextQueueSeq;
                            return new AdmitResult(AdmitStatus.QUEUE_FULL, null);
                        }
                        OutboxRecord record = new OutboxRecord(c.nextQueueSeq, eventId, event, policy, dedupe,
                            occurred.longValue(), queued.longValue(), expiresAt, accounted);
                        tx.putOutbox(record);
                        tx.putIssuedEventId(new IssuedEventId(eventId, occurred.longValue() + EVENT_ID_RETENTION_MS));
                        tx.putCounters(c.withNextQueueSeq(c.nextQueueSeq + 1).withClockHighWaterMs(clockMark));
                        kept.add(record);
                        mirror.addAll(kept);
                        nextSeqOut[0] = c.nextQueueSeq + 1;
                        return new AdmitResult(AdmitStatus.ADMITTED, record);
                    }
                });
            } catch (StoreException e) {
                // SPEC 15.1: storage failure MUST NOT claim the queueing.
                pendingExpiredRollback();
                return new AdmitResult(AdmitStatus.STORAGE_FAILED, null);
            }
            if (result.status == AdmitStatus.ADMITTED || result.status == AdmitStatus.QUEUE_FULL) {
                records = mirror;
                nextSeq = nextSeqOut[0];
                highWater = Math.max(highWater, now);
                pendingExpiredCommit();
            }
            return result;
        }
    }

    // pendingExpired is only advanced once the transaction that deleted the
    // records commits; a rollback discards the tentative count.
    private int tentativeExpired = 0;
    private void pendingExpiredIncrement() { tentativeExpired++; }
    private void pendingExpiredRollback() { tentativeExpired = 0; }
    private void pendingExpiredCommit() { pendingExpired += tentativeExpired; tentativeExpired = 0; }

    // ------------------------------------------------------ expiry (15.2)

    /** SPEC 15.2: delete expired records before delivery, advancing the
     * durable clock mark.  Returns the number deleted. */
    public int sweepExpired() {
        synchronized (monitor) {
            final long now = effectiveNowLocked();
            final Long protectedSeq = inFlightSeq;
            boolean anyExpired = false;
            for (OutboxRecord r : records)
                if (r.expiresAtMs <= now && (protectedSeq == null || protectedSeq.longValue() != r.queueSeq)) anyExpired = true;
            if (!anyExpired && now <= highWater) return 0;
            final List<OutboxRecord> mirror = new ArrayList<OutboxRecord>();
            final int[] expired = new int[1];
            try {
                store.write(pairingId, new CompanionStore.WriteBlock<Void>() {
                    @Override public Void apply(WriteTx tx) throws StoreException {
                        Counters c = tx.counters();
                        long clockMark = Math.max(c.clockHighWaterMs, now);
                        for (OutboxRecord r : tx.outbox()) {
                            boolean protectedRecord = protectedSeq != null && protectedSeq.longValue() == r.queueSeq;
                            if (!protectedRecord && r.expiresAtMs <= clockMark) {
                                tx.deleteOutbox(r.queueSeq);
                                expired[0]++;
                            } else mirror.add(r);
                        }
                        tx.putCounters(c.withClockHighWaterMs(clockMark));
                        return null;
                    }
                });
            } catch (StoreException e) {
                // Deletion that fails to persist re-runs next sweep.
                return 0;
            }
            records = mirror;
            highWater = Math.max(highWater, now);
            pendingExpired += expired[0];
            return expired[0];
        }
    }

    // ---------------------------------------------------- delivery (15.3)

    /**
     * SPEC 15.3: atomically pick the next deliverable head and mark it
     * in-flight.  Sweeps expired first; a head at or beyond BARRIER-SEQ (the
     * SYNCING replay barrier; null = no barrier) yields BARRIER_HELD; an
     * empty queue yields EMPTY.  The caller MUST clearInFlight(seq) when the
     * delivery reaches a permanent result.
     */
    public Delivery beginDelivery(Long barrierSeq) {
        synchronized (monitor) {
            sweepExpired();
            if (records.isEmpty()) return new Delivery(DeliveryKind.EMPTY, null);
            OutboxRecord head = records.get(0);
            if (barrierSeq != null && head.queueSeq >= barrierSeq.longValue())
                return new Delivery(DeliveryKind.BARRIER_HELD, null);
            inFlightSeq = Long.valueOf(head.queueSeq);
            return new Delivery(DeliveryKind.READY, head);
        }
    }

    /** Release the in-flight marker iff it is still SEQ — idempotent, so a
     * stale releaser cannot clear a newer delivery's marker. */
    public void clearInFlight(Long seq) {
        synchronized (monitor) {
            if (seq != null && inFlightSeq != null && inFlightSeq.longValue() == seq.longValue()) inFlightSeq = null;
        }
    }

    /** SPEC 15.3: delete only after a permanent result.  A failed persist
     * leaves the record durable; redelivery is answered `duplicate` by the
     * Emacs receipt store (14.4), so at-least-once holds either way. */
    public void deleteRecord(final long seq) {
        synchronized (monitor) {
            final List<OutboxRecord> mirror = new ArrayList<OutboxRecord>();
            try {
                store.write(pairingId, new CompanionStore.WriteBlock<Void>() {
                    @Override public Void apply(WriteTx tx) throws StoreException {
                        tx.deleteOutbox(seq);
                        mirror.addAll(tx.outbox());
                        return null;
                    }
                });
                records = mirror;
            } catch (StoreException e) {
                // retained; see above
            }
            if (inFlightSeq != null && inFlightSeq.longValue() == seq) inFlightSeq = null;
        }
    }

    /** SPEC 10.4: persist the READY disconnection time. */
    public void recordReadyDisconnect(final long atMs) {
        synchronized (monitor) {
            try {
                store.write(pairingId, new CompanionStore.WriteBlock<Void>() {
                    @Override public Void apply(WriteTx tx) throws StoreException {
                        tx.putCounters(tx.counters().withReadyDisconnectedAtMs(Long.valueOf(atMs)));
                        return null;
                    }
                });
            } catch (StoreException e) {
                // best effort: staleness timing only
            }
        }
    }
}
