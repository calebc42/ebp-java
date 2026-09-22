// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.store;

import java.util.List;

/**
 * One pairing-confined durable transaction (SPEC 3, 13.2, 15.1).  Operations
 * are EBP records, never tables or cursors.  The scope is invalid once
 * {@link CompanionStore#write} returns or throws.
 */
public interface WriteTx {
    Counters counters() throws StoreException;
    void putCounters(Counters counters) throws StoreException;

    SurfaceRecord surface(String surfaceId) throws StoreException;
    List<SurfaceRecord> surfaces() throws StoreException;
    void putSurface(SurfaceRecord record) throws StoreException;
    void deleteSurface(String surfaceId) throws StoreException;

    /** Ascending queue_seq. */
    List<OutboxRecord> outbox() throws StoreException;
    void putOutbox(OutboxRecord record) throws StoreException;
    void deleteOutbox(long queueSeq) throws StoreException;

    List<IssuedEventId> issuedEventIds() throws StoreException;
    void putIssuedEventId(IssuedEventId record) throws StoreException;
    void deleteIssuedEventId(String eventId) throws StoreException;
}
