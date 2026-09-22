// SPDX-License-Identifier: GPL-3.0-or-later
// Rollback-capable in-memory reference store: every write applies to a
// working copy and installs it only on success.  Host tests only — an
// in-memory store is not evidence of crash recovery.
package com.calebc42.ebp.companion.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MemoryStore implements CompanionStore {

    /** Mutable working state of one partition. */
    protected static final class Partition {
        Counters counters = Counters.initial();
        final Map<String, SurfaceRecord> surfaces = new HashMap<String, SurfaceRecord>();
        final Map<Long, OutboxRecord> outbox = new HashMap<Long, OutboxRecord>();
        final Map<String, IssuedEventId> issued = new HashMap<String, IssuedEventId>();

        Partition copy() {
            Partition p = new Partition();
            p.counters = counters;
            p.surfaces.putAll(surfaces);
            p.outbox.putAll(outbox);
            p.issued.putAll(issued);
            return p;
        }

        Snapshot snapshot() {
            List<SurfaceRecord> s = new ArrayList<SurfaceRecord>(surfaces.values());
            java.util.Collections.sort(s, new java.util.Comparator<SurfaceRecord>() {
                @Override public int compare(SurfaceRecord a, SurfaceRecord b) {
                    return a.firstSeenOrdinal < b.firstSeenOrdinal ? -1
                        : a.firstSeenOrdinal == b.firstSeenOrdinal ? 0 : 1;
                }
            });
            List<OutboxRecord> o = sortedOutbox(outbox);
            return new Snapshot(counters, s, o, new ArrayList<IssuedEventId>(issued.values()));
        }
    }

    static List<OutboxRecord> sortedOutbox(Map<Long, OutboxRecord> outbox) {
        List<OutboxRecord> o = new ArrayList<OutboxRecord>(outbox.values());
        java.util.Collections.sort(o, new java.util.Comparator<OutboxRecord>() {
            @Override public int compare(OutboxRecord a, OutboxRecord b) {
                return a.queueSeq < b.queueSeq ? -1 : a.queueSeq == b.queueSeq ? 0 : 1;
            }
        });
        return o;
    }

    /** The transaction view over a working copy. */
    protected static final class Tx implements WriteTx {
        final Partition p;
        Tx(Partition p) { this.p = p; }
        @Override public Counters counters() { return p.counters; }
        @Override public void putCounters(Counters counters) { p.counters = counters; }
        @Override public SurfaceRecord surface(String surfaceId) { return p.surfaces.get(surfaceId); }
        @Override public List<SurfaceRecord> surfaces() { return p.snapshot().surfaces; }
        @Override public void putSurface(SurfaceRecord record) { p.surfaces.put(record.surface, record); }
        @Override public void deleteSurface(String surfaceId) { p.surfaces.remove(surfaceId); }
        @Override public List<OutboxRecord> outbox() { return sortedOutbox(p.outbox); }
        @Override public void putOutbox(OutboxRecord record) { p.outbox.put(Long.valueOf(record.queueSeq), record); }
        @Override public void deleteOutbox(long queueSeq) { p.outbox.remove(Long.valueOf(queueSeq)); }
        @Override public List<IssuedEventId> issuedEventIds() { return new ArrayList<IssuedEventId>(p.issued.values()); }
        @Override public void putIssuedEventId(IssuedEventId record) { p.issued.put(record.eventId, record); }
        @Override public void deleteIssuedEventId(String eventId) { p.issued.remove(eventId); }
    }

    private final Map<String, Partition> partitions = new HashMap<String, Partition>();

    protected Partition partition(String pairingId) {
        Partition p = partitions.get(pairingId);
        if (p == null) { p = new Partition(); partitions.put(pairingId, p); }
        return p;
    }

    @Override public synchronized Snapshot load(String pairingId) throws StoreException {
        return partition(pairingId).snapshot();
    }

    @Override public synchronized <T> T write(String pairingId, WriteBlock<T> block) throws StoreException {
        Partition working = partition(pairingId).copy();
        T result;
        try {
            result = block.apply(new Tx(working));
        } catch (StoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        }
        commit(pairingId, working);
        partitions.put(pairingId, working);
        return result;
    }

    /** Subclasses persist the committed working copy here; a throw rolls back. */
    protected void commit(String pairingId, Partition working) throws StoreException {}

    @Override public synchronized void erasePairing(String pairingId) throws StoreException {
        Partition empty = new Partition();
        commit(pairingId, empty);
        partitions.remove(pairingId);
    }

    @Override public void close() {}
}
