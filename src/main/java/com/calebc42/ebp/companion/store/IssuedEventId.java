// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.store;

/** SPEC 14.4: an EventId MUST NOT be reused within 604800 seconds, including
 * across process restart; this record outlives the outbox row that used it. */
public final class IssuedEventId {
    public final String eventId;
    public final long reusableAfterMs;

    public IssuedEventId(String eventId, long reusableAfterMs) {
        if (eventId == null) throw new NullPointerException();
        this.eventId = eventId;
        this.reusableAfterMs = reusableAfterMs;
    }
}
