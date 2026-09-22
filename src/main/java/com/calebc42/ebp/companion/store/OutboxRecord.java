// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.store;

import com.calebc42.ebp.companion.json.Json;

/** Complete immutable payload and metadata for one durable event
 * occurrence (SPEC 15.1).  `event` is the complete `event.action` params. */
public final class OutboxRecord {
    public final long queueSeq;
    public final String eventId;
    public final Json.Obj event;
    public final String policy;      // "queue" or "wake"
    public final String dedupe;      // null when none
    public final long occurredAtMs;
    public final long queuedAtMs;
    public final long expiresAtMs;
    public final long accountedBytes;

    public OutboxRecord(long queueSeq, String eventId, Json.Obj event, String policy, String dedupe,
                        long occurredAtMs, long queuedAtMs, long expiresAtMs, long accountedBytes) {
        if (queueSeq < 1) throw new IllegalArgumentException("queueSeq");
        if (eventId == null || event == null || policy == null) throw new NullPointerException();
        if (!policy.equals("queue") && !policy.equals("wake")) throw new IllegalArgumentException("policy");
        if (queuedAtMs < occurredAtMs || expiresAtMs < queuedAtMs) throw new IllegalArgumentException("times");
        if (accountedBytes <= 0) throw new IllegalArgumentException("accountedBytes");
        String payloadId = event.string("event_id");
        if (payloadId == null || !payloadId.equals(eventId))
            throw new IllegalArgumentException("payload event_id must match eventId");
        this.queueSeq = queueSeq;
        this.eventId = eventId;
        this.event = event;
        this.policy = policy;
        this.dedupe = dedupe;
        this.occurredAtMs = occurredAtMs;
        this.queuedAtMs = queuedAtMs;
        this.expiresAtMs = expiresAtMs;
        this.accountedBytes = accountedBytes;
    }
}
