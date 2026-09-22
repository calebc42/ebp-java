// SPDX-License-Identifier: GPL-3.0-or-later
// The device-lifetime Companion: configuration, the per-pairing durable
// surface and queue state shared across connections and restarts, the
// newest-session-wins rule (SPEC 5.6), and the SPEC 14 action pipeline
// from a presented descriptor to a durable outbox record or a live
// delivery.  Everything here runs on one ordered executor supplied by the
// host; the host's transport reader threads only hand bytes to
// {@link #submit}.
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.auth.Auth;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.rpc.SessionState;
import com.calebc42.ebp.companion.store.CompanionStore;
import com.calebc42.ebp.companion.store.StoreException;
import com.calebc42.ebp.companion.store.SurfaceRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

public final class Companion {

    /** Presentation and diagnostics hooks.  Called on the ordered executor;
     * a host must not block in them. */
    public interface Listener {
        void surfaceChanged(String surface);
        void sessionChanged(SessionState state);
        void queueChanged();
        void settingsRequested();
        void diagnostic(String message);
    }

    /** SPEC 5.8: the host's wake mechanism, if its platform defines one.
     * Called after a `wake` occurrence is durably admitted with no READY
     * session (SPEC 15.1), from the ordered executor; the handler owns the
     * coalescing, the 60-second and 10-second bounds, and the target
     * re-check the section requires. */
    public interface WakeHandler {
        void wake(String pairingId);
    }

    private volatile WakeHandler wakeHandler;

    /** Install the SPEC 5.8 wake mechanism; null (the default) means the
     * platform defines none and `offline.wake` must not be granted. */
    public void setWakeHandler(WakeHandler handler) { this.wakeHandler = handler; }

    /** SPEC 14.4 admission outcomes visible to the host. */
    public enum Admission {
        QUEUED, DELIVERED_LIVE, DROPPED_OFFLINE, NOT_PRESENTED, INVALID_CONTEXT, QUEUE_FULL,
        STORAGE_FAILED, TOO_LARGE, LOCAL_BUILTIN
    }

    private final CompanionConfig config;
    private final CompanionStore store;
    private final Clock clock;
    private final Executor executor;
    private final Listener listener;
    private final Map<String, SurfaceStore> surfaceStores = new HashMap<String, SurfaceStore>();
    private final Map<String, DurableQueue> queues = new HashMap<String, DurableQueue>();
    private CompanionEngine current;
    private long generation = 0;
    private String lastError;

    public Companion(CompanionConfig config, CompanionStore store, Clock clock, Executor executor, Listener listener) {
        this.config = config;
        this.store = store;
        this.clock = clock;
        this.executor = executor;
        this.listener = listener;
    }

    public CompanionConfig config() { return config; }
    public Clock clock() { return clock; }
    public synchronized long generation() { return generation; }
    public synchronized String lastError() { return lastError; }

    /** Run TASK on the ordered executor. */
    public void submit(Runnable task) { executor.execute(task); }

    /** Create the engine for a freshly accepted connection. */
    public synchronized CompanionEngine open(CompanionEngine.Sink sink) {
        return new CompanionEngine(this, sink);
    }

    public synchronized CompanionEngine currentEngine() { return current; }

    // ---------------------------------------------- per-pairing state

    synchronized SurfaceStore surfacesFor(String pairingId) {
        SurfaceStore s = surfaceStores.get(pairingId);
        if (s == null) {
            try {
                s = new SurfaceStore(store, pairingId, config.limit("max_surfaces"),
                    config.limit("max_surface_ids"), clock);
            } catch (StoreException e) {
                throw new IllegalStateException(e);
            }
            surfaceStores.put(pairingId, s);
        }
        return s;
    }

    synchronized DurableQueue queueFor(String pairingId) {
        DurableQueue q = queues.get(pairingId);
        if (q == null) {
            try {
                q = new DurableQueue(store, pairingId, config.limit("max_queued_events"),
                    config.limit("max_queued_bytes"), clock);
            } catch (StoreException e) {
                throw new IllegalStateException(e);
            }
            queues.put(pairingId, q);
        }
        return q;
    }

    /** The one pairing this Companion is provisioned with, when exactly one. */
    public String solePairingId() {
        return config.pairings.size() == 1 ? config.pairings.keySet().iterator().next() : null;
    }

    // ------------------------------------------------ engine callbacks

    synchronized void authenticated(CompanionEngine engine) {
        CompanionEngine previous = current;
        current = engine;
        if (previous != null && previous != engine) previous.supersede();
        generation++;
        listener.sessionChanged(engine.state());
    }

    synchronized void engineClosed(CompanionEngine engine, SessionState previous) {
        if (current == engine) {
            current = null;
            generation++;
        }
        listener.sessionChanged(SessionState.CLOSED);
    }

    synchronized void sessionChanged(CompanionEngine engine) {
        if (engine == current || current == null) listener.sessionChanged(engine.state());
    }

    synchronized void surfaceChanged(String surface) {
        generation++;
        listener.surfaceChanged(surface);
    }

    synchronized void queueChanged() { listener.queueChanged(); }

    synchronized void eventConcluded(long seq, String status) { generation++; }

    synchronized void diagnostic(String message) {
        lastError = message;
        listener.diagnostic(message);
    }

    synchronized void peerDiagnostic(Json.Obj params) {
        // Peer log.error is diagnostic only; never an acknowledgement.
        Long code = params.integer("code");
        String msg = params.string("message");
        listener.diagnostic("peer log.error " + code + ": " + (msg == null ? "" : msg));
    }

    // ------------------------------------------------ presentation

    /** The current presentation of SURFACE for the sole pairing. */
    public synchronized Presentation presentation(String pairingId, String surface) {
        SurfaceStore s = surfacesFor(pairingId);
        SurfaceRecord r = s.record(surface);
        if (r == null) return Presentation.absent(surface, -1);
        return Presentation.of(r, config.appProfile);
    }

    public synchronized Json.Obj welcomeSurfaces(String pairingId) {
        return surfacesFor(pairingId).welcomeSurfaces();
    }

    public synchronized int queuedCount(String pairingId) { return queueFor(pairingId).count(); }

    public synchronized boolean inFlight(String pairingId) { return queueFor(pairingId).hasInFlight(); }

    // ------------------------------------------------ actions (14)

    /**
     * SPEC 14: an activation of the button at PATH, presented at REVISION on
     * SURFACE.  The descriptor and enabled state are re-validated against
     * the accepted snapshot; a stale revision or a vanished node is a safe
     * no-op.  Durable policies commit before any delivery attempt (SPEC
     * 15.1); `drop` is delivered only on a READY session.
     */
    public synchronized Admission activate(String pairingId, String surface, long revision, String path) {
        SurfaceStore s = surfacesFor(pairingId);
        SurfaceRecord r = s.record(surface);
        if (r == null || !r.present || r.revision != revision) return Admission.NOT_PRESENTED;
        Presentation p = Presentation.of(r, config.appProfile);
        Json.Obj node = p.nodeAt(path);
        if (node == null || !"button".equals(node.string("t"))) return Admission.NOT_PRESENTED;
        if (!node.boolOr("enabled", true)) return Admission.INVALID_CONTEXT;
        Json.Obj descriptor = node.object("on_tap");
        if (descriptor == null) return Admission.INVALID_CONTEXT;
        return dispatchDescriptor(pairingId, surface, revision, descriptor, r);
    }

    private Admission dispatchDescriptor(String pairingId, String surface, long revision, Json.Obj descriptor,
                                         SurfaceRecord record) {
        String builtin = descriptor.string("builtin");
        if (builtin != null) return executeBuiltin(pairingId, surface, revision, builtin, descriptor, record);
        String action = descriptor.string("action");
        String policy = descriptor.stringOr("when_offline", Vocabulary.OFFLINE_DEFAULT);
        DurableQueue queue = queueFor(pairingId);
        long now = queue.effectiveNow();
        Json.Obj event = new Json.Obj().put("event_id", Auth.generateHex16(config.random)).put("action", action);
        Json.Obj args = descriptor.object("args");
        if (args != null) event.put("args", args);
        event.put("surface", surface).put("revision_seen", revision).put("occurred_at_ms", now);
        return deliverEvent(pairingId, event, policy, descriptor.string("dedupe"), descriptor.integral("ttl_s"), now);
    }

    private Admission deliverEvent(String pairingId, Json.Obj event, String policy, String dedupe, Long ttl, long now) {
        DurableQueue queue = queueFor(pairingId);
        CompanionEngine engine = current;
        boolean ready = engine != null && engine.state() == SessionState.READY
            && pairingId.equals(engine.pairingId());
        if (policy.equals("queue") || policy.equals("wake")) {
            // SPEC 15.1/22.3: persistence precedes every delivery attempt,
            // including on an already-READY session; queued_at_ms rides it.
            Json.Obj durable = event.with("queued_at_ms", Json.Num.of(now));
            if (Json.utf8Length(Json.write(durable)) > config.limit("max_event_bytes")) {
                diagnostic("event exceeds max_event_bytes");
                return Admission.TOO_LARGE;
            }
            DurableQueue.AdmitResult admitted = queue.admit(durable, policy, dedupe, ttl.longValue());
            switch (admitted.status) {
                case ADMITTED:
                    generation++;
                    listener.queueChanged();
                    if (ready) engine.kick();
                    else if (policy.equals("wake")) {
                        // SPEC 15.1: persisted, and only then the profile's
                        // wake mechanism (SPEC 5.8), when the host has one.
                        WakeHandler w = wakeHandler;
                        if (w != null) w.wake(pairingId);
                    }
                    return Admission.QUEUED;
                case QUEUE_FULL:
                    diagnostic("queue full (1601 queue-full)");
                    return Admission.QUEUE_FULL;
                case EVENT_ID_REUSED:
                    diagnostic("event id reused before retention expired");
                    return Admission.STORAGE_FAILED;
                default:
                    diagnostic("storage failed: the interaction was NOT queued");
                    return Admission.STORAGE_FAILED;
            }
        }
        // drop: live delivery only, never promoted to durable storage.
        if (Json.utf8Length(Json.write(event)) > config.limit("max_event_bytes")) {
            diagnostic("event exceeds max_event_bytes");
            return Admission.TOO_LARGE;
        }
        if (!ready) {
            diagnostic("no READY session: drop action discarded");
            return Admission.DROPPED_OFFLINE;
        }
        engine.sendRequest("event.action", event, new CompanionEngine.Callback() {
            @Override public void done(Json.Obj result, Json.Obj error) {
                String status = result == null ? null : result.string("status");
                if (error != null) diagnostic("live action concluded with error " + error.integer("code"));
                else if (!("accepted".equals(status) || "duplicate".equals(status)))
                    diagnostic("live action concluded " + status);
                synchronized (Companion.this) { generation++; }
                listener.queueChanged();
            }
        });
        return Admission.DELIVERED_LIVE;
    }

    private Admission executeBuiltin(String pairingId, String surface, long revision, String builtin,
                                     Json.Obj descriptor, SurfaceRecord record) {
        if (builtin.equals("companion.settings.open")) {
            listener.settingsRequested();
            return Admission.LOCAL_BUILTIN;
        }
        if (builtin.equals("view.switch")) {
            String view = descriptor.string("view");
            SurfaceStore s = surfacesFor(pairingId);
            if (view == null || !s.switchView(surface, view)) return Admission.INVALID_CONTEXT;
            generation++;
            listener.surfaceChanged(surface);
            // SPEC 14.2: if READY, report the remote action `view.switched`
            // with args.view and when_offline drop.
            DurableQueue queue = queueFor(pairingId);
            long now = queue.effectiveNow();
            Json.Obj event = new Json.Obj().put("event_id", Auth.generateHex16(config.random))
                .put("action", "view.switched").put("args", new Json.Obj().put("view", view))
                .put("surface", surface).put("revision_seen", revision).put("occurred_at_ms", now);
            deliverEvent(pairingId, event, "drop", null, null, now);
            return Admission.LOCAL_BUILTIN;
        }
        return Admission.INVALID_CONTEXT;
    }

    // ------------------------------------------------ revocation (9.1)

    /** SPEC 9.1: fence and erase one pairing's state; the caller has already
     * removed its credential and closed its sessions. */
    public synchronized void revokePairing(String pairingId) throws StoreException {
        if (current != null && pairingId.equals(current.pairingId())) current.close("pairing revoked");
        surfaceStores.remove(pairingId);
        queues.remove(pairingId);
        store.erasePairing(pairingId);
        generation++;
    }
}
