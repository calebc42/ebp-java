// SPDX-License-Identifier: GPL-3.0-or-later
// The Companion's surface state (SPEC 13): revisioned snapshots, tombstones
// that survive until pairing revocation, the surface-count limits, and the
// welcome report — over the transactional CompanionStore, so `applied` is
// never returned before the accepted snapshot and its floor are durable.
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.rpc.Envelope;
import com.calebc42.ebp.companion.store.CompanionStore;
import com.calebc42.ebp.companion.store.Counters;
import com.calebc42.ebp.companion.store.Snapshot;
import com.calebc42.ebp.companion.store.StoreException;
import com.calebc42.ebp.companion.store.SurfaceRecord;
import com.calebc42.ebp.companion.store.WriteTx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SurfaceStore {

    public static final class Result {
        public final String status;   // "applied" or "stale"
        public final long revision;
        public final boolean present;
        Result(String status, long revision, boolean present) {
            this.status = status; this.revision = revision; this.present = present;
        }
    }

    /** Thrown when a validated mutation could not be committed durably; the
     * engine answers -32603 and the in-memory state is unchanged. */
    public static final class PersistenceFailed extends Exception {
        private static final long serialVersionUID = 1L;
        PersistenceFailed(StoreException cause) { super(cause); }
    }

    private final CompanionStore store;
    private final String pairingId;
    private final long maxSurfaces;
    private final long maxSurfaceIds;
    private final Clock clock;
    // Acceptance order (first-seen ordinal), mirrored from the durable store.
    private final LinkedHashMap<String, SurfaceRecord> records = new LinkedHashMap<String, SurfaceRecord>();
    private final Object monitor = new Object();

    public SurfaceStore(CompanionStore store, String pairingId, long maxSurfaces, long maxSurfaceIds,
                        Clock clock) throws StoreException {
        this.store = store;
        this.pairingId = pairingId;
        this.maxSurfaces = maxSurfaces;
        this.maxSurfaceIds = maxSurfaceIds;
        this.clock = clock;
        // SPEC 10.4/13.1: the store outlives connections AND process death.
        Snapshot snapshot = store.load(pairingId);
        for (SurfaceRecord r : snapshot.surfaces) records.put(r.surface, r);
    }

    // ---------------------------------------------------------- identifiers

    /** SPEC 13.1: `<namespace>:<name>`, the whole ID a bounded identifier. */
    public static boolean isValidSurfaceId(String id) {
        if (id == null) return false;
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) return false;
        String ns = id.substring(0, colon);
        if (!(ns.equals("app") || ns.equals("notification") || ns.equals("widget") || ns.equals("tile")))
            return false;
        String name = id.substring(colon + 1);
        return Envelope.isIdentifier(name) && Envelope.isBoundedIdentifier(id);
    }

    public static boolean isAppSurfaceId(String id) {
        return isValidSurfaceId(id) && id.startsWith("app:");
    }

    public static String namespace(String id) { return id.substring(0, id.indexOf(':')); }

    // -------------------------------------------------------------- reads

    /** The accepted snapshot for a present surface, for rendering. */
    public SurfaceRecord record(String surface) {
        synchronized (monitor) { return records.get(surface); }
    }

    public Json.Obj spec(String surface) {
        SurfaceRecord r = record(surface);
        return r != null && r.present ? r.spec : null;
    }

    /** SPEC 14.4: the accepted revision the user is looking at. */
    public Long revisionOf(String surface) {
        SurfaceRecord r = record(surface);
        return r != null && r.present ? Long.valueOf(r.revision) : null;
    }

    public List<String> presentSurfaces() {
        synchronized (monitor) {
            List<String> out = new ArrayList<String>();
            for (SurfaceRecord r : records.values()) if (r.present) out.add(r.surface);
            return out;
        }
    }

    /** SPEC 10.2: both present snapshots and tombstones are reported; a
     * present multi-view app surface also reports its current view. */
    public Json.Obj welcomeSurfaces() {
        synchronized (monitor) {
            Json.Obj out = new Json.Obj();
            for (SurfaceRecord r : records.values()) {
                Json.Obj entry = new Json.Obj().put("revision", r.revision).put("present", r.present);
                if (r.present && r.currentView != null && r.surface.startsWith("app:"))
                    entry.put("current_view", r.currentView);
                out.put(r.surface, entry);
            }
            return out;
        }
    }

    public Map<String, SurfaceRecord> all() {
        synchronized (monitor) { return Collections.unmodifiableMap(new LinkedHashMap<String, SurfaceRecord>(records)); }
    }

    // ------------------------------------------------------ update (13.2)

    /** Apply an already validated update.  VALIDATION is the validator's
     * result for SPEC (multi-view or not).  Returns applied/stale; throws
     * ContentInvalid for a limit violation and PersistenceFailed when the
     * durable commit failed (state unchanged). */
    public Result update(final String surface, final long revision, final Json.Obj spec,
                         final SpecValidator.Result validation, final Json.Obj staleSpec,
                         final Long staleAfterS, final String requestedView)
        throws PersistenceFailed {
        synchronized (monitor) {
            SurfaceRecord record = records.get(surface);
            long floor = record == null ? -1L : record.revision;
            if (revision <= floor) return new Result("stale", floor, record != null && record.present);
            // SPEC 13.1: limits gate anything that would grow a saturated count.
            if (record == null) {
                if (records.size() >= maxSurfaceIds) throw new ContentInvalid("surface", "surface-limit");
                if (presentCount() >= maxSurfaces) throw new ContentInvalid("surface", "surface-limit");
            } else if (!record.present && presentCount() >= maxSurfaces) {
                // Reactivating a tombstone is invalid at max_surfaces.
                throw new ContentInvalid("surface", "surface-limit");
            }
            // SPEC 13.4: preserve the user's view unless it vanished, the
            // surface is new, or the request names one.
            String nextView;
            if (!validation.multiView) nextView = null;
            else if (requestedView != null) nextView = requestedView;
            else if (record != null && record.present && record.currentView != null
                     && spec.object("views").has(record.currentView)) nextView = record.currentView;
            else nextView = spec.string("initial_view");
            final String view = nextView;
            final long now = clock.wallMs();
            final SurfaceRecord prior = record;
            Failpoints.hit(Failpoints.SURFACE_BEFORE_COMMIT);
            SurfaceRecord committed;
            try {
                committed = store.write(pairingId, new CompanionStore.WriteBlock<SurfaceRecord>() {
                    @Override public SurfaceRecord apply(WriteTx tx) throws StoreException {
                        long firstSeen;
                        if (prior != null) firstSeen = prior.firstSeenOrdinal;
                        else {
                            Counters c = tx.counters();
                            firstSeen = c.nextSurfaceOrdinal;
                            tx.putCounters(c.withNextSurfaceOrdinal(firstSeen + 1));
                        }
                        SurfaceRecord next = new SurfaceRecord(surface, revision, true, spec, staleSpec,
                            staleAfterS, view, now, firstSeen);
                        tx.putSurface(next);
                        return next;
                    }
                });
            } catch (StoreException e) {
                throw new PersistenceFailed(e);
            }
            records.put(surface, committed);
            return new Result("applied", revision, true);
        }
    }

    // ------------------------------------------------------ remove (13.3)

    public Result remove(final String surface, final long revision) throws PersistenceFailed {
        synchronized (monitor) {
            SurfaceRecord record = records.get(surface);
            long floor = record == null ? -1L : record.revision;
            if (revision <= floor) return new Result("stale", floor, record != null && record.present);
            if (record == null && records.size() >= maxSurfaceIds)
                throw new ContentInvalid("surface", "surface-limit");
            final SurfaceRecord prior = record;
            final long now = clock.wallMs();
            Failpoints.hit(Failpoints.SURFACE_BEFORE_COMMIT);
            SurfaceRecord committed;
            try {
                committed = store.write(pairingId, new CompanionStore.WriteBlock<SurfaceRecord>() {
                    @Override public SurfaceRecord apply(WriteTx tx) throws StoreException {
                        long firstSeen;
                        if (prior != null) firstSeen = prior.firstSeenOrdinal;
                        else {
                            Counters c = tx.counters();
                            firstSeen = c.nextSurfaceOrdinal;
                            tx.putCounters(c.withNextSurfaceOrdinal(firstSeen + 1));
                        }
                        SurfaceRecord next = SurfaceRecord.tombstone(surface, revision, now, firstSeen);
                        tx.putSurface(next);
                        return next;
                    }
                });
            } catch (StoreException e) {
                throw new PersistenceFailed(e);
            }
            records.put(surface, committed);
            return new Result("applied", revision, false);
        }
    }

    // ----------------------------------------------------- release (13.1)

    /** SPEC 13.1 `surface.release`: retire a tombstoned surface's history.
     * Returns true when released, false when no history exists; throws
     * ContentInvalid for a present surface. */
    public boolean release(final String surface) throws PersistenceFailed {
        synchronized (monitor) {
            SurfaceRecord record = records.get(surface);
            if (record == null) return false;
            if (record.present) throw new ContentInvalid("surface", "present surfaces are removed before release");
            try {
                store.write(pairingId, new CompanionStore.WriteBlock<Void>() {
                    @Override public Void apply(WriteTx tx) throws StoreException {
                        tx.deleteSurface(surface);
                        return null;
                    }
                });
            } catch (StoreException e) {
                throw new PersistenceFailed(e);
            }
            records.remove(surface);
            return true;
        }
    }

    // --------------------------------------------------- view.switch (14.2)

    /** SPEC 14.2 view.switch: valid only inside a present multi-view surface
     * whose snapshot names VIEW; the choice persists like other record
     * state.  Returns whether the switch happened. */
    public boolean switchView(final String surface, final String view) {
        synchronized (monitor) {
            SurfaceRecord r = records.get(surface);
            if (r == null || !r.present || r.spec == null) return false;
            Json.Obj views = r.spec.object("views");
            if (views == null || !views.has(view)) return false;
            if (view.equals(r.currentView)) return true;
            final SurfaceRecord next = r.withCurrentView(view);
            try {
                store.write(pairingId, new CompanionStore.WriteBlock<Void>() {
                    @Override public Void apply(WriteTx tx) throws StoreException {
                        tx.putSurface(next);
                        return null;
                    }
                });
            } catch (StoreException e) {
                return false; // the view choice is presentation state; nothing claimed
            }
            records.put(surface, next);
            return true;
        }
    }

    private long presentCount() {
        long n = 0;
        for (SurfaceRecord r : records.values()) if (r.present) n++;
        return n;
    }
}
