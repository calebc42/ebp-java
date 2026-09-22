// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.store;

import com.calebc42.ebp.companion.json.Json;

/** Accepted EBP surface state (SPEC 13.1-13.5).  A tombstone retains its
 * revision floor and first-seen ordinal but no presentation payload. */
public final class SurfaceRecord {
    public final String surface;
    public final long revision;
    public final boolean present;
    public final Json.Obj spec;          // null for a tombstone
    public final Json.Obj staleSpec;     // null unless authored
    public final Long staleAfterS;       // null unless authored
    public final String currentView;     // null unless multi-view and present
    public final long acceptedAtMs;
    public final long firstSeenOrdinal;

    public SurfaceRecord(String surface, long revision, boolean present, Json.Obj spec,
                         Json.Obj staleSpec, Long staleAfterS, String currentView,
                         long acceptedAtMs, long firstSeenOrdinal) {
        if (surface == null || surface.isEmpty()) throw new IllegalArgumentException("surface");
        if (revision < 0) throw new IllegalArgumentException("revision");
        if (firstSeenOrdinal < 1) throw new IllegalArgumentException("firstSeenOrdinal");
        if (present) {
            if (spec == null) throw new IllegalArgumentException("a present surface requires its spec");
            if (staleAfterS != null && staleAfterS.longValue() <= 0)
                throw new IllegalArgumentException("stale_after_s");
        } else if (spec != null || staleSpec != null || staleAfterS != null || currentView != null) {
            throw new IllegalArgumentException("a tombstone retains no presentation payload");
        }
        this.surface = surface;
        this.revision = revision;
        this.present = present;
        this.spec = spec;
        this.staleSpec = staleSpec;
        this.staleAfterS = staleAfterS;
        this.currentView = currentView;
        this.acceptedAtMs = acceptedAtMs;
        this.firstSeenOrdinal = firstSeenOrdinal;
    }

    public static SurfaceRecord tombstone(String surface, long revision, long acceptedAtMs, long firstSeen) {
        return new SurfaceRecord(surface, revision, false, null, null, null, null, acceptedAtMs, firstSeen);
    }

    public SurfaceRecord withCurrentView(String view) {
        return new SurfaceRecord(surface, revision, present, spec, staleSpec, staleAfterS, view,
            acceptedAtMs, firstSeenOrdinal);
    }
}
