// SPDX-License-Identifier: GPL-3.0-or-later
// The Companion's per-connection session engine: SPEC 9 handshake
// validation, SPEC 10 lifecycle with fail-closed pre-auth behavior, the
// welcome, SPEC 7.3 dispatch rules, SPEC 13 surface requests, and the SPEC
// 15 single-flight durable delivery pump.  Transport-agnostic: feed()
// consumes bytes, the sink receives outbound frames.  Every method is
// invoked on the Companion's one ordered executor.
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.auth.Auth;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.rpc.Envelope;
import com.calebc42.ebp.companion.rpc.MethodRegistry;
import com.calebc42.ebp.companion.rpc.SessionState;
import com.calebc42.ebp.companion.store.OutboxRecord;
import com.calebc42.ebp.companion.wire.FrameClose;
import com.calebc42.ebp.companion.wire.FrameDecoder;
import com.calebc42.ebp.companion.wire.FrameEncoder;
import com.calebc42.ebp.companion.wire.FrameIncomplete;
import com.calebc42.ebp.companion.wire.InvalidRequest;
import com.calebc42.ebp.companion.wire.WireParseError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CompanionEngine {

    /** One synchronous transport handoff; a throw closes the engine. */
    public interface Sink {
        void write(byte[] frame) throws java.io.IOException;
    }

    /** Result/error continuation for a Companion-originated request. */
    public interface Callback {
        void done(Json.Obj result, Json.Obj error);
    }

    private static final int PENDING_HOLD = 512;
    private static final int PENDING_RESUME = 128;

    private final Companion companion;
    private final CompanionConfig config;
    private final Sink sink;
    private final FrameDecoder decoder = new FrameDecoder();

    private SessionState state = SessionState.CONNECTED;
    private String closeReason;
    private String pendingPairingId;
    private String pendingClientNonce;
    private String pendingServerNonce;
    private List<String> lastWants = new ArrayList<String>();
    private Set<String> granted = new HashSet<String>();
    private long sessionBoundarySeq = Long.MAX_VALUE;

    // Per-pairing shared state, bound after authentication.
    private SurfaceStore surfaces;
    private DurableQueue queue;

    // Outbound requests (SPEC 7.2: integer ids).
    private long nextOutboundId = 0;
    private final Map<Long, Callback> pending = new HashMap<Long, Callback>();
    private boolean pendingHeld = false;

    // Durable delivery pump (SPEC 15.3).
    private boolean pumpPaused = false;
    private Json.Value replayId = null;
    private int replayDelivered = 0;
    private int replayRejected = 0;
    private Json.Value blockedBy = Json.Null.NULL;
    private Long myInFlightSeq = null;

    CompanionEngine(Companion companion, Sink sink) {
        this.companion = companion;
        this.config = companion.config();
        this.sink = sink;
    }

    public synchronized SessionState state() { return state; }
    public synchronized String closeReason() { return closeReason; }
    public synchronized String pairingId() { return state == SessionState.CONNECTED ? null : pendingPairingId; }
    public synchronized boolean pumpPaused() { return pumpPaused; }
    synchronized int outstandingRequestCount() { return pending.size(); }

    // ------------------------------------------------------------- inbound

    public synchronized void feed(byte[] bytes, int offset, int length) {
        if (state == SessionState.CLOSED) return;
        // A recoverable body fault unwinds the decoder's drain loop, so any
        // frames pipelined behind the bad one stay buffered; re-entering with
        // no new bytes drains them (the decoder already advanced past the bad
        // frame).  Every pass either drains to completion or consumes at least
        // one more whole frame, so this terminates.
        int sourceOffset = offset, sourceLength = length;
        boolean drain = true;
        while (drain) {
            drain = false;
            try {
                decoder.feed(bytes, sourceOffset, sourceLength, new FrameDecoder.Consumer() {
                    @Override public void accept(Json.Obj msg) {
                        if (state != SessionState.CLOSED) {
                            try {
                                dispatch(msg);
                            } catch (Failpoints.Tripped t) {
                                throw t;
                            } catch (RuntimeException e) {
                                // A dispatch failure must fail closed, never
                                // crash-loop the host.
                                companion.diagnostic("dispatch failure: " + e);
                                close("dispatch failure: " + e.getMessage());
                            }
                        }
                    }
                });
            } catch (FrameClose e) {
                close("frame: " + e.getMessage());
                return;
            } catch (FrameIncomplete e) {
                close("frame: " + e.getMessage());
                return;
            } catch (WireParseError e) {
                // SPEC 6.2: a complete body that is invalid UTF-8 or JSON gets
                // a Parse Error with id:null; the stream stayed synchronized,
                // so the connection MAY (and here does) continue.
                emitFramingError(-32700, "Parse error", "parse-error");
                if (state != SessionState.CLOSED) { sourceOffset = 0; sourceLength = 0; drain = true; }
            } catch (InvalidRequest e) {
                emitFramingError(-32600, "Invalid Request", "invalid-request");
                if (state != SessionState.CLOSED) { sourceOffset = 0; sourceLength = 0; drain = true; }
            }
        }
    }

    /** SPEC 6.2: EOF from the transport. */
    public synchronized void endOfStream() {
        if (state == SessionState.CLOSED) return;
        try {
            decoder.finish();
            close("transport closed");
        } catch (FrameIncomplete e) {
            close("frame: " + e.getMessage());
        }
    }

    public synchronized void close(String reason) {
        if (state == SessionState.CLOSED) return;
        SessionState previous = state;
        state = SessionState.CLOSED;
        closeReason = reason;
        // SPEC 15.3: the in-flight marker is connection state; the record
        // returns to plain queued so the next session's replay can move.
        if (queue != null) queue.clearInFlight(myInFlightSeq);
        myInFlightSeq = null;
        // SPEC 10.4: persist the READY disconnection time.
        if (previous == SessionState.READY && queue != null) queue.recordReadyDisconnect(queue.effectiveNow());
        // SPEC 22.3: outstanding requests fail locally, exactly once each.
        if (!pending.isEmpty()) {
            List<Callback> callbacks = new ArrayList<Callback>(pending.values());
            pending.clear();
            Json.Obj err = new Json.Obj().put("code", -32603).put("message", "Connection closed")
                .put("data", new Json.Obj().put("kind", "connection-closed"));
            for (Callback cb : callbacks) {
                try { cb.done(null, err); } catch (RuntimeException ignored) { /* best effort */ }
            }
        }
        if (replayId != null) replayId = null;
        companion.engineClosed(this, previous);
    }

    private void dispatch(Json.Obj msg) {
        Envelope.MessageClass cls = Envelope.classify(msg);
        if (cls == null) {
            // SPEC 7.3: structurally invalid; answer only when an id exists.
            if (msg.has("id") && msg.has("method"))
                respondError(msg.get("id"), -32600, "Invalid Request", "invalid-request", null);
            return;
        }
        switch (cls) {
            case REQUEST:
                handleRequest(msg.get("id"), Envelope.methodOf(msg), msg.get("params"));
                break;
            case NOTIFICATION:
                handleNotification(Envelope.methodOf(msg), msg.get("params"));
                break;
            case RESPONSE: {
                // SPEC 7.2: Companion-issued ids are integers; a response
                // carrying the STRING "1" never concludes the integer id 1.
                Long key = msg.get("id").integerOrNull();
                Callback cb = key == null ? null : pending.remove(key);
                if (cb != null) {
                    Json.Obj result = msg.object("result");
                    Json.Obj error = msg.object("error");
                    if (result == null && error == null) {
                        // A response whose result is not an object cannot be
                        // consumed; treat as a protocol violation for the pump.
                        error = new Json.Obj().put("code", -32603).put("message", "Malformed response")
                            .put("data", new Json.Obj().put("kind", "internal-error"));
                        cb.done(null, error);
                    } else {
                        cb.done(result, error);
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------ requests

    private void handleRequest(Json.Value id, String method, Json.Value rawParams) {
        // SPEC 7.2: ids are strings or safe integers.
        if (!Envelope.isValidRequestId(id)) {
            respondError(id, -32600, "Invalid Request", "invalid-request", null);
            return;
        }
        // SPEC 10.1: fail closed before authentication on method and state,
        // BEFORE params.
        if (state == SessionState.CONNECTED && !method.equals("session.hello")) {
            respondError(id, 1200, "Not authenticated", "not-authenticated", null);
            return;
        }
        if (state == SessionState.CHALLENGED && !method.equals("auth.response")) {
            respondError(id, 1200, "Not authenticated", "not-authenticated", null);
            return;
        }
        // SPEC 4.1/7.3: params, when present, MUST be an object.
        Json.Obj params;
        if (rawParams == null) params = new Json.Obj();
        else if (rawParams instanceof Json.Obj) params = (Json.Obj) rawParams;
        else { respondError(id, -32602, "Invalid params", "invalid-params", null); return; }
        if (state == SessionState.CONNECTED) { handleHello(id, params); return; }
        if (state == SessionState.CHALLENGED) { handleAuth(id, params); return; }
        // SPEC 11/4.5: a method name that is not a §4.4 identifier within 128
        // octets is -32601 WITHOUT consulting the registry.
        if (!Envelope.isValidMethodName(method)) {
            respondError(id, -32601, "Method not found", "method-not-found", null);
            return;
        }
        MethodRegistry.Spec spec = MethodRegistry.lookup(method);
        if (spec == null) {
            respondError(id, -32601, "Method not found", "method-not-found", null);
            return;
        }
        if (spec.sender == MethodRegistry.Sender.COMPANION || !spec.isRequest) {
            respondError(id, -32600, "Invalid Request", "invalid-request", null);
            return;
        }
        if (!spec.states.contains(state)) {
            respondError(id, 1204, "Not legal in this session state", "session-state", null);
            return;
        }
        if (method.equals("surface.update")) handleSurfaceUpdate(id, params);
        else if (method.equals("surface.remove")) handleSurfaceRemove(id, params);
        else if (method.equals("surface.release")) handleSurfaceRelease(id, params);
        else if (method.equals("queue.replay")) handleQueueReplay(id);
        else if (method.equals("session.ready")) handleReady(id);
        else {
            // Every remaining request is capability-gated and this Companion
            // grants none of those capabilities.
            respondError(id, 1001, "Capability not granted", "cap-unsupported", null);
        }
    }

    private void handleNotification(String method, Json.Value rawParams) {
        // Pre-auth (SPEC 10.1) and unknown/wrong-direction (SPEC 7.3)
        // notifications are logged and dropped; nothing is emitted.
        if (state == SessionState.CONNECTED || state == SessionState.CHALLENGED) return;
        if (!Envelope.isValidMethodName(method)) return;
        MethodRegistry.Spec spec = MethodRegistry.lookup(method);
        if (spec == null || spec.sender == MethodRegistry.Sender.COMPANION || spec.isRequest) return;
        if (!spec.states.contains(state)) return;
        if (!(rawParams instanceof Json.Obj)) return;
        Json.Obj params = (Json.Obj) rawParams;
        if (method.equals("log.error")) {
            companion.peerDiagnostic(params);
        } else if (method.equals("rpc.cancel")) {
            // SPEC 7.5: this Companion keeps no user-paced request outstanding
            // (no dialogs are granted); cancellation of a completed or unknown
            // id is ignored.
        }
        // theme.set, toast.show, ... are capability-gated and never granted.
    }

    // ----------------------------------------------------- handshake (9)

    private void handleHello(Json.Value id, Json.Obj params) {
        // Integer SPELLING only: 3.0 and "3" are mismatches.
        Long protocol = params.integer("protocol");
        if (protocol == null || protocol.longValue() != Auth.PROTOCOL_VERSION) {
            respondError(id, 1202, "Unsupported protocol major", "protocol-version",
                new Json.Obj().put("supported", new Json.Arr().add(Auth.PROTOCOL_VERSION)));
            return;
        }
        Json.Obj client = params.object("client");
        String pairingId = params.string("pairing_id");
        String clientNonce = params.string("client_nonce");
        boolean clientOk = client != null && client.size() == 2 && client.has("name") && client.has("version")
            && boundedNonEmpty(client.string("name")) && boundedNonEmpty(client.string("version"));
        Json.Arr wants = params.array("wants");
        List<String> wantsList = new ArrayList<String>();
        boolean wantsOk = wants != null && wants.size() <= 128;
        if (wantsOk) {
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < wants.size() && wantsOk; i++) {
                String w = wants.get(i).stringOrNull();
                if (w == null || !Envelope.isBoundedIdentifier(w) || !seen.add(w)) wantsOk = false;
                else wantsList.add(w);
            }
        }
        if (!clientOk || pairingId == null || !Auth.isValidPairingId(pairingId) || clientNonce == null
            || !Auth.isValidNonce(clientNonce) || !wantsOk) {
            respondError(id, -32602, "Invalid params", "invalid-params", null);
            return;
        }
        // SPEC 9.1: never reveal whether the pairing ID is known; challenge
        // regardless and fail at the proof.
        lastWants = wantsList;
        pendingPairingId = pairingId;
        pendingClientNonce = clientNonce;
        pendingServerNonce = Auth.generateHex16(config.random);
        respondResult(id, new Json.Obj().put("server_nonce", pendingServerNonce));
        state = SessionState.step(state, SessionState.Event.HELLO_ACCEPTED);
        companion.sessionChanged(this);
    }

    private static boolean boundedNonEmpty(String s) {
        return s != null && !s.isEmpty() && Json.utf8Length(s) <= 128;
    }

    private void handleAuth(Json.Value id, Json.Obj params) {
        String pid = params.string("pairing_id");
        String cn = params.string("client_nonce");
        String sn = params.string("server_nonce");
        String proof = params.string("client_proof");
        // SPEC 9.3: type/grammar failures are -32602 followed by close.
        if (pid == null || !Auth.isValidPairingId(pid) || cn == null || !Auth.isValidNonce(cn)
            || sn == null || !Auth.isValidNonce(sn) || proof == null || !Auth.isValidProof(proof)) {
            respondError(id, -32602, "Invalid params", "invalid-params", null);
            close("malformed auth.response");
            return;
        }
        // SPEC 9.2: an unknown pairing ID is verified with work equivalent to
        // the known-ID path — an HMAC against a fixed dummy key and a
        // constant-time comparison — so neither the failure stage, the error
        // code, nor timing distinguishes the two.
        byte[] token = config.pairings.get(pendingPairingId);
        boolean known = token != null;
        byte[] key = known ? token : Auth.DUMMY_KEY;
        boolean proofOk = Auth.verifyClientProof(proof, key, pid, cn, sn);
        boolean ok = Auth.constantTimeEquals(pid, pendingPairingId)
            & Auth.constantTimeEquals(cn, pendingClientNonce)
            & Auth.constantTimeEquals(sn, pendingServerNonce)
            & known & proofOk;
        if (!ok) {
            respondError(id, 1203, "Authentication failed", "auth-failed", null);
            close("auth failed");
            return;
        }
        // Bind the pairing's durable state before the welcome reports it.
        try {
            surfaces = companion.surfacesFor(pendingPairingId);
            queue = companion.queueFor(pendingPairingId);
        } catch (RuntimeException e) {
            respondError(id, -32603, "Storage failed", "internal-error", null);
            close("store unavailable: " + e.getMessage());
            return;
        }
        String serverProof = Auth.serverProof(token, pendingPairingId, pendingClientNonce, pendingServerNonce);
        respondResult(id, buildWelcome(serverProof));
        state = SessionState.step(state, SessionState.Event.AUTH_VERIFIED);
        companion.authenticated(this);
    }

    private Json.Obj buildWelcome(String serverProof) {
        granted = new HashSet<String>();
        Json.Arr grantedArr = new Json.Arr();
        for (String w : lastWants)
            if (config.supportedCapabilities.contains(w)) { granted.add(w); grantedArr.add(w); }
        sessionBoundarySeq = queue.boundarySeq();
        queue.sweepExpired();
        Json.Obj welcome = new Json.Obj().put("server_proof", serverProof).put("protocol", Auth.PROTOCOL_VERSION)
            .put("server", new Json.Obj().put("name", config.serverName).put("version", config.serverVersion))
            .put("granted", grantedArr).put("surface_profiles", config.surfaceProfiles)
            .put("surfaces", surfaces.welcomeSurfaces())
            // SPEC 10.2: the count visible to the next replay.
            .put("queued_events", queue.count())
            .put("limits", config.limits);
        // SPEC 10.2: input_state MUST be omitted when empty; this Companion
        // advertises no stateful node, so it is always empty.
        return welcome;
    }

    // ------------------------------------------------------ session.ready

    private void handleReady(Json.Value id) {
        // SPEC 10.3: the {} response serializes ahead of every READY-only
        // frame; emitting before transitioning does that.
        respondResult(id, new Json.Obj());
        state = SessionState.step(state, SessionState.Event.READY_CONFIRMED);
        // (No divergent input state to flush: no stateful nodes exist here.)
        companion.sessionChanged(this);
        // SPEC 15.3: only after that flush may events flow.
        pumpAdvance();
    }

    // -------------------------------------------------- replay pump (15)

    private void handleQueueReplay(Json.Value id) {
        // SPEC 15.3: only one replay may be active.
        if (replayId != null) {
            respondError(id, 1600, "A replay is already active", "queue-busy", null);
            return;
        }
        replayId = id;
        replayDelivered = 0;
        replayRejected = 0;
        blockedBy = Json.Null.NULL;
        pumpPaused = false; // an explicit replay resumes a paused pump
        queue.sweepExpired();
        // SPEC 15.3: join an in-flight durable request rather than duplicate
        // it; its disposition lands in this summary via onPumpResult.
        if (!queue.hasInFlight()) pumpAdvance();
    }

    /** Called by the Companion after a durable admission while this session
     * is READY (SPEC 15.3: the pump runs whenever READY and non-empty). */
    synchronized void kick() {
        if (state == SessionState.READY) pumpAdvance();
    }

    private void pumpAdvance() {
        if (queue == null) return;
        if (queue.hasInFlight() || pumpPaused) {
            if (pumpPaused) concludeReplay();
            return;
        }
        // Auto-delivery needs READY; an explicit replay drives the pump in
        // SYNCING too — that IS the 10.3 barrier draining the backlog.
        boolean replayActive = replayId != null;
        if (!replayActive && state != SessionState.READY) return;
        if (replayActive && state != SessionState.SYNCING && state != SessionState.READY) return;
        Long barrier = state == SessionState.SYNCING ? Long.valueOf(sessionBoundarySeq) : null;
        DurableQueue.Delivery d = queue.beginDelivery(barrier);
        switch (d.kind) {
            case READY: {
                final long seq = d.record.queueSeq;
                myInFlightSeq = Long.valueOf(seq);
                Failpoints.hit(Failpoints.OUTBOX_AFTER_COMMIT_BEFORE_SEND);
                // SPEC 15.3: the stored record replays with its stored
                // event_id; single-flight by construction, so exempt from the
                // outstanding-request ceiling.
                sendRequestInternal("event.action", d.record.event, false, new Callback() {
                    @Override public void done(Json.Obj result, Json.Obj error) { onPumpResult(seq, result, error); }
                });
                companion.queueChanged();
                break;
            }
            case EMPTY:
            case BARRIER_HELD:
            default:
                concludeReplay();
                break;
        }
    }

    private void onPumpResult(long seq, Json.Obj result, Json.Obj error) {
        queue.clearInFlight(Long.valueOf(seq));
        myInFlightSeq = null;
        String status = result == null ? null : result.string("status");
        if (error != null) {
            // SPEC 15.3: any well-formed error retains the head and pauses
            // the pump; later admissions never bypass it.
            pumpPaused = true;
            Json.Obj data = error.object("data");
            String kind = data == null ? null : data.string("kind");
            blockedBy = new Json.Str(kind == null || kind.isEmpty() ? "json-rpc-error" : kind);
            concludeReplay();
        } else if ("accepted".equals(status) || "duplicate".equals(status)) {
            replayDelivered++;
            Failpoints.hit(Failpoints.EVENT_AFTER_RESULT_BEFORE_DELETE);
            queue.deleteRecord(seq);
            companion.eventConcluded(seq, status);
            pumpAdvance();
        } else if ("stale".equals(status) || "rejected".equals(status)) {
            replayRejected++;
            queue.deleteRecord(seq);
            companion.eventConcluded(seq, status);
            companion.diagnostic("event " + seq + " concluded " + status);
            pumpAdvance();
        } else {
            // SPEC 15.3: unknown status is a protocol violation — retain the
            // event, one safe log.error, close.
            emit(Envelope.notification("log.error", new Json.Obj().put("code", -32603)
                .put("message", "event.action result with unknown status")
                .put("data", new Json.Obj().put("kind", "internal-error"))));
            close("event.action result with unknown status");
        }
        companion.queueChanged();
    }

    private void concludeReplay() {
        Json.Value id = replayId;
        if (id == null) return;
        replayId = null;
        respondResult(id, new Json.Obj().put("delivered", replayDelivered).put("rejected", replayRejected)
            .put("expired", queue.takeExpiredCount()).put("remaining", queue.count())
            .put("blocked_by", blockedBy));
    }

    // ------------------------------------------------------ surfaces (13)

    private static Long revisionOf(Json.Value v) {
        // Integer SPELLING only: 1.0 and "1" are -32602.
        Long n = v == null ? null : v.integerOrNull();
        return n != null && n.longValue() >= 0 ? n : null;
    }

    private void handleSurfaceUpdate(Json.Value id, Json.Obj params) {
        String surface = params.string("surface");
        Long revision = revisionOf(params.get("revision"));
        Json.Obj spec = params.object("spec");
        if (surface == null || revision == null || spec == null) {
            respondError(id, -32602, "Invalid params", "invalid-params", null);
            return;
        }
        if (!SurfaceStore.isValidSurfaceId(surface)) {
            respondError(id, 1201, "Invalid content", "content-invalid", new Json.Obj().put("reason", "surface-id"));
            return;
        }
        // SPEC 13.1: the namespace's capability must have been granted; this
        // Companion offers only the core `app` namespace.
        if (!SurfaceStore.namespace(surface).equals("app")) {
            respondError(id, 1201, "Invalid content", "content-invalid",
                new Json.Obj().put("reason", "namespace-not-granted"));
            return;
        }
        for (String key : params.keys()) {
            if (!(key.equals("surface") || key.equals("revision") || key.equals("spec") || key.equals("stale_after_s")
                  || key.equals("stale_spec") || key.equals("current_view") || key.equals("reset_input_ids"))) {
                // SPEC 12 rule 1: unknown optional members are ignored.
            }
        }
        try {
            Long staleAfterS = null;
            if (params.has("stale_after_s")) {
                staleAfterS = params.get("stale_after_s").integralOrNull();
                if (staleAfterS == null || staleAfterS.longValue() <= 0)
                    throw new ContentInvalid("stale_after_s", "must be a positive integer");
            }
            SpecValidator.Context ctx = new SpecValidator.Context(config.appProfile,
                config.limit("max_capture_fields"), config.limitOr("max_canvas_ops", Long.MAX_VALUE),
                granted.contains("offline.wake"));
            SpecValidator.Result validation = SpecValidator.validateAppSpec(spec, "spec", ctx);
            Json.Obj staleSpec = null;
            if (params.has("stale_spec")) {
                Json.Value ss = params.get("stale_spec");
                SpecValidator.validateStaleSpec(ss, validation.multiView, "stale_spec", ctx);
                staleSpec = (Json.Obj) ss;
            }
            String currentView = null;
            if (params.has("current_view")) {
                currentView = params.string("current_view");
                if (currentView == null || !validation.multiView || !spec.object("views").has(currentView))
                    throw new ContentInvalid("current_view", "valid only for a multi-view app spec naming an existing view");
            }
            if (params.has("reset_input_ids")) {
                Json.Arr reset = params.array("reset_input_ids");
                if (reset == null) throw new ContentInvalid("reset_input_ids", "must be an array of widget IDs");
                // No stateful node type is advertised, so any named ID cannot
                // be a non-password stateful node of this spec.
                if (reset.size() > 0)
                    throw new ContentInvalid("reset_input_ids[0]", "must name a non-password stateful node in spec");
            }
            SurfaceStore.Result result = surfaces.update(surface, revision.longValue(), spec, validation,
                staleSpec, staleAfterS, currentView);
            Failpoints.hit(Failpoints.SURFACE_AFTER_COMMIT_BEFORE_REPLY);
            respondResult(id, new Json.Obj().put("status", result.status).put("revision", result.revision)
                .put("present", result.present));
            if (result.status.equals("applied")) companion.surfaceChanged(surface);
        } catch (ContentInvalid e) {
            respondError(id, 1201, "Invalid content", "content-invalid",
                new Json.Obj().put("path", e.path).put("reason", e.reason));
        } catch (SurfaceStore.PersistenceFailed e) {
            // SPEC 13.2: never claim `applied` before the snapshot is durable.
            companion.diagnostic("surface persistence failed: " + e.getCause());
            respondError(id, -32603, "Storage failed", "internal-error", null);
        }
    }

    private void handleSurfaceRemove(Json.Value id, Json.Obj params) {
        String surface = params.string("surface");
        Long revision = revisionOf(params.get("revision"));
        if (surface == null || revision == null) {
            respondError(id, -32602, "Invalid params", "invalid-params", null);
            return;
        }
        // SPEC 13.1: a structurally invalid ID must not become a tombstone.
        if (!SurfaceStore.isValidSurfaceId(surface)) {
            respondError(id, 1201, "Invalid content", "content-invalid", new Json.Obj().put("reason", "surface-id"));
            return;
        }
        try {
            SurfaceStore.Result result = surfaces.remove(surface, revision.longValue());
            Failpoints.hit(Failpoints.SURFACE_AFTER_COMMIT_BEFORE_REPLY);
            respondResult(id, new Json.Obj().put("status", result.status).put("revision", result.revision)
                .put("present", result.present));
            if (result.status.equals("applied")) companion.surfaceChanged(surface);
        } catch (ContentInvalid e) {
            respondError(id, 1201, "Invalid content", "content-invalid",
                new Json.Obj().put("path", e.path).put("reason", e.reason));
        } catch (SurfaceStore.PersistenceFailed e) {
            companion.diagnostic("surface persistence failed: " + e.getCause());
            respondError(id, -32603, "Storage failed", "internal-error", null);
        }
    }

    private void handleSurfaceRelease(Json.Value id, Json.Obj params) {
        String surface = params.string("surface");
        if (surface == null || params.size() != 1) {
            respondError(id, -32602, "Invalid params", "invalid-params", null);
            return;
        }
        if (!SurfaceStore.isValidSurfaceId(surface)) {
            respondError(id, 1201, "Invalid content", "content-invalid", new Json.Obj().put("reason", "surface-id"));
            return;
        }
        try {
            boolean released = surfaces.release(surface);
            respondResult(id, new Json.Obj().put("released", released));
            if (released) companion.surfaceChanged(surface);
        } catch (ContentInvalid e) {
            respondError(id, 1201, "Invalid content", "content-invalid",
                new Json.Obj().put("path", e.path).put("reason", e.reason));
        } catch (SurfaceStore.PersistenceFailed e) {
            respondError(id, -32603, "Storage failed", "internal-error", null);
        }
    }

    // ------------------------------------------- outbound requests (7)

    /** Send a Companion-originated request with an integer id (SPEC 7.2).
     * A request refused by the outstanding-request bound fails locally with
     * a synthetic 1401; the callback always runs exactly once. */
    public synchronized void sendRequest(String method, Json.Obj params, Callback callback) {
        sendRequestInternal(method, params, true, callback);
    }

    private void sendRequestInternal(String method, Json.Obj params, boolean bounded, Callback callback) {
        if (state == SessionState.CLOSED) {
            callback.done(null, new Json.Obj().put("code", -32603).put("message", "Connection closed")
                .put("data", new Json.Obj().put("kind", "connection-closed")));
            return;
        }
        // SPEC 22.3: the outstanding-request count is a MUST-bound resource;
        // a fixed ring that holds off at HOLD and resumes below RESUME.
        if (pendingHeld && pending.size() <= PENDING_RESUME) pendingHeld = false;
        if (bounded && (pendingHeld || pending.size() >= PENDING_HOLD)) {
            pendingHeld = true;
            callback.done(null, new Json.Obj().put("code", 1401).put("message", "Outstanding requests exhausted")
                .put("data", new Json.Obj().put("kind", "overloaded")));
            return;
        }
        long id = ++nextOutboundId;
        pending.put(Long.valueOf(id), callback);
        if (!emit(Envelope.request(Json.Num.of(id), method, params))) {
            // emit closed the engine and already failed every pending callback.
        }
    }

    /** SPEC 5.6/10.1: this session is replaced by a newer authenticated one. */
    synchronized void supersede() {
        if (state == SessionState.SYNCING || state == SessionState.READY)
            emit(Envelope.notification("session.superseded", new Json.Obj()));
        close("superseded");
    }

    // ------------------------------------------------------------- output

    private void respondResult(Json.Value id, Json.Obj result) {
        emit(Envelope.result(id, result));
    }

    private void respondError(Json.Value id, int code, String message, String kind, Json.Obj data) {
        // SPEC 7.2: a request id is never null; a framing-level error carries
        // id:null through emitFramingError instead.
        if (Envelope.isValidRequestId(id)) emit(Envelope.error(id, code, message, kind, data));
    }

    private void emitFramingError(int code, String message, String kind) {
        if (state == SessionState.CLOSED) return;
        emit(Envelope.error(Json.Null.NULL, code, message, kind, null));
    }

    private boolean emit(Json.Obj msg) {
        String body;
        try {
            body = Json.write(msg);
        } catch (RuntimeException e) {
            // SPEC 7.1: a responder that cannot serialize a result answers
            // -32603 rather than leaving the request unanswered.
            Json.Value id = msg.get("id");
            if (id != null && msg.has("result"))
                return emit(Envelope.error(id, -32603, "Internal error", "internal-error", null));
            return false;
        }
        byte[] frame;
        try {
            frame = FrameEncoder.encode(body);
        } catch (RuntimeException e) {
            Json.Value id = msg.get("id");
            if (id != null && msg.has("result"))
                return emit(Envelope.error(id, -32603, "Internal error", "internal-error", null));
            return false;
        }
        try {
            sink.write(frame);
            return true;
        } catch (java.io.IOException e) {
            close("transport write failed");
            return false;
        } catch (RuntimeException e) {
            close("transport write failed");
            return false;
        }
    }
}
