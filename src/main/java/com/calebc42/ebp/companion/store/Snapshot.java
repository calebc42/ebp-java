// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.store;

import java.util.Collections;
import java.util.List;

/** Immutable restore result for one pairing partition. */
public final class Snapshot {
    public final Counters counters;
    public final List<SurfaceRecord> surfaces;       // acceptance order (first-seen ordinal)
    public final List<OutboxRecord> outbox;          // ascending queue_seq
    public final List<IssuedEventId> issuedEventIds;

    public Snapshot(Counters counters, List<SurfaceRecord> surfaces, List<OutboxRecord> outbox,
                    List<IssuedEventId> issuedEventIds) {
        this.counters = counters;
        this.surfaces = Collections.unmodifiableList(surfaces);
        this.outbox = Collections.unmodifiableList(outbox);
        this.issuedEventIds = Collections.unmodifiableList(issuedEventIds);
    }
}
