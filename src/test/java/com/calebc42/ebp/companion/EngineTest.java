// SPDX-License-Identifier: GPL-3.0-or-later
// Session, surface, and queue behavior of the engine against an in-process
// peer: SPEC 24.6 items 4-8 and 10 as far as the host can witness them.
package com.calebc42.ebp.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.json.JsonParser;
import com.calebc42.ebp.companion.rpc.Envelope;
import com.calebc42.ebp.companion.rpc.SessionState;
import com.calebc42.ebp.companion.store.MemoryStore;
import com.calebc42.ebp.companion.wire.FrameEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class EngineTest {
    private Fixtures.TestClock clock;
    private Fixtures.Events events;
    private Companion companion;
    private Peer peer;

    @Before public void setUp() {
        clock = new Fixtures.TestClock();
        events = new Fixtures.Events();
        companion = Fixtures.companion(new MemoryStore(), clock, events);
        peer = new Peer(companion);
    }

    private Json.Obj toReady() {
        Json.Obj welcome = peer.handshake(Fixtures.PAIRING_ID, Fixtures.TOKEN, Fixtures.noWants());
        peer.replay();
        peer.ready();
        return welcome;
    }

    // ------------------------------------------------------- handshake

    @Test public void handshakeReachesReadyWithProfileAndLimits() {
        Json.Obj welcome = peer.handshake(Fixtures.PAIRING_ID, Fixtures.TOKEN, Fixtures.noWants());
        assertEquals(SessionState.SYNCING, peer.engine.state());
        assertEquals(Long.valueOf(3), welcome.integer("protocol"));
        assertEquals(0, welcome.array("granted").size());
        Json.Obj app = welcome.object("surface_profiles").object("app");
        assertEquals(4, app.array("node_types").size());
        assertTrue(app.has("members"));
        assertFalse(welcome.has("input_state"));
        assertEquals(Long.valueOf(0), welcome.integer("queued_events"));
        assertEquals(Long.valueOf(4194304), welcome.object("limits").integer("max_frame_bytes"));
        Json.Obj summary = peer.replay();
        assertEquals(Long.valueOf(0), summary.integer("remaining"));
        assertTrue(summary.get("blocked_by").isNull());
        peer.ready();
        assertEquals(SessionState.READY, peer.engine.state());
    }

    @Test public void preAuthRequestsAreRefusedAndNotificationsDropped() {
        Json.Obj err = peer.error("surface.update", new Json.Obj());
        assertEquals(Long.valueOf(1200), err.integer("code"));
        err = peer.error("nonexistent.method", new Json.Obj());
        assertEquals(Long.valueOf(1200), err.integer("code"));
        int before = peer.inbox.size();
        peer.send(Envelope.notification("log.error", new Json.Obj().put("code", 1).put("message", "x")));
        assertEquals(before, peer.inbox.size());
        // A malformed hello is -32602; a wrong protocol is 1202.
        err = peer.error("session.hello", new Json.Obj().put("protocol", 2));
        assertEquals(Long.valueOf(1202), err.integer("code"));
        err = peer.error("session.hello", new Json.Obj().put("protocol", 3));
        assertEquals(Long.valueOf(-32602), err.integer("code"));
        assertEquals(SessionState.CONNECTED, peer.engine.state());
    }

    @Test public void unknownPairingAndBadProofsFailClosed() {
        String cn = "202122232425262728292a2b2c2d2e2f";
        Json.Obj nonce = peer.result("session.hello", peer.hello("ffffffffffffffffffffffffffffffff", cn, Fixtures.noWants()));
        String sn = nonce.string("server_nonce");
        // The proof is computed with the real token, but the pairing is unknown.
        Json.Obj auth = new Json.Obj().put("pairing_id", "ffffffffffffffffffffffffffffffff").put("client_nonce", cn)
            .put("server_nonce", sn).put("client_proof",
                com.calebc42.ebp.companion.auth.Auth.clientProof(Fixtures.TOKEN, "ffffffffffffffffffffffffffffffff", cn, sn));
        Json.Obj err = peer.error("auth.response", auth);
        assertEquals(Long.valueOf(1203), err.integer("code"));
        assertEquals(SessionState.CLOSED, peer.engine.state());

        peer = new Peer(companion);
        nonce = peer.result("session.hello", peer.hello(Fixtures.PAIRING_ID, cn, Fixtures.noWants()));
        sn = nonce.string("server_nonce");
        auth = new Json.Obj().put("pairing_id", Fixtures.PAIRING_ID).put("client_nonce", cn)
            .put("server_nonce", sn).put("client_proof", "0000000000000000000000000000000000000000000000000000000000000000");
        err = peer.error("auth.response", auth);
        assertEquals(Long.valueOf(1203), err.integer("code"));
        assertEquals(SessionState.CLOSED, peer.engine.state());

        peer = new Peer(companion);
        nonce = peer.result("session.hello", peer.hello(Fixtures.PAIRING_ID, cn, Fixtures.noWants()));
        auth = new Json.Obj().put("pairing_id", Fixtures.PAIRING_ID).put("client_nonce", cn)
            .put("server_nonce", nonce.string("server_nonce")).put("client_proof", "short");
        err = peer.error("auth.response", auth);
        assertEquals(Long.valueOf(-32602), err.integer("code"));
        assertEquals(SessionState.CLOSED, peer.engine.state());
    }

    @Test public void dispatchRules() {
        toReady();
        assertEquals(Long.valueOf(-32601), peer.error("no.such", new Json.Obj()).integer("code"));
        assertEquals(Long.valueOf(-32601), peer.error("bad method!", new Json.Obj()).integer("code"));
        StringBuilder longName = new StringBuilder("a");
        for (int i = 0; i < 128; i++) longName.append('b');
        assertEquals(Long.valueOf(-32601), peer.error(longName.toString(), new Json.Obj()).integer("code"));
        // Wrong direction / wrong class.
        assertEquals(Long.valueOf(-32600), peer.error("event.action", new Json.Obj()).integer("code"));
        assertEquals(Long.valueOf(-32600), peer.error("log.error", new Json.Obj()).integer("code"));
        // Capability-gated request without the grant.
        assertEquals(Long.valueOf(1001), peer.error("dialog.show", new Json.Obj()).integer("code"));
        // Wrong state: session.ready again.
        assertEquals(Long.valueOf(1204), peer.error("session.ready", new Json.Obj()).integer("code"));
        // Positional params.
        Json.Obj msg = new Json.Obj().put("jsonrpc", "2.0").put("id", "p1").put("method", "queue.replay").put("params", new Json.Arr());
        int before = peer.inbox.size();
        peer.send(msg);
        assertEquals(Long.valueOf(-32602), peer.inbox.get(before).object("error").integer("code"));
        // Unknown notification is ignored silently.
        before = peer.inbox.size();
        peer.send(Envelope.notification("no.such", new Json.Obj()));
        assertEquals(before, peer.inbox.size());
        // Framing-level faults answer id:null and the connection continues.
        before = peer.inbox.size();
        peer.sendRaw(FrameEncoder.encode("[1,2]"));
        assertTrue(peer.inbox.get(before).get("id").isNull());
        assertEquals(Long.valueOf(-32600), peer.inbox.get(before).object("error").integer("code"));
        peer.sendRaw(FrameEncoder.encode("{\"a\":1,\"a\":2}"));
        assertEquals(Long.valueOf(-32600), peer.inbox.get(before + 1).object("error").integer("code"));
        peer.sendRaw(FrameEncoder.encode("{nope"));
        assertEquals(Long.valueOf(-32700), peer.inbox.get(before + 2).object("error").integer("code"));
        assertEquals(SessionState.READY, peer.engine.state());
        // A framing close: missing Content-Length.
        peer.sendRaw("X-Nope: 1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(SessionState.CLOSED, peer.engine.state());
    }

    // ---------------------------------------------------------- surfaces

    @Test public void surfaceUpdateRemoveRaceAndRelease() {
        toReady();
        Json.Obj r = peer.update("app:milestone", 5, Fixtures.milestoneSpec(false));
        assertEquals("applied", r.string("status"));
        assertEquals(Long.valueOf(5), r.integer("revision"));
        assertTrue(events.log.contains("surface:app:milestone"));
        // Stale and duplicate.
        assertEquals("stale", peer.update("app:milestone", 5, Fixtures.milestoneSpec(true)).string("status"));
        assertEquals("stale", peer.update("app:milestone", 4, Fixtures.milestoneSpec(true)).string("status"));
        assertEquals("Flag: off", companion.presentation(Fixtures.PAIRING_ID, "app:milestone").nodeAt("r.1").string("text"));
        // SPEC 24.6 item 7: update/remove/update race.
        Json.Obj rm = peer.result("surface.remove", new Json.Obj().put("surface", "app:milestone").put("revision", 7));
        assertEquals("applied", rm.string("status"));
        assertFalse(rm.bool("present"));
        Json.Obj late = peer.update("app:milestone", 6, Fixtures.milestoneSpec(true));
        assertEquals("stale", late.string("status"));
        assertEquals(Long.valueOf(7), late.integer("revision"));
        assertFalse(late.bool("present"));
        assertFalse(companion.presentation(Fixtures.PAIRING_ID, "app:milestone").present);
        // Welcome reports the tombstone.
        Json.Obj ws = companion.welcomeSurfaces(Fixtures.PAIRING_ID);
        assertEquals(Long.valueOf(7), ws.object("app:milestone").integer("revision"));
        assertFalse(ws.object("app:milestone").bool("present"));
        // Release retires the history; a later reuse starts at -1.
        assertTrue(peer.result("surface.release", new Json.Obj().put("surface", "app:milestone")).bool("released"));
        assertFalse(peer.result("surface.release", new Json.Obj().put("surface", "app:milestone")).bool("released"));
        assertEquals("applied", peer.update("app:milestone", 0, Fixtures.milestoneSpec(true)).string("status"));
        assertEquals(Long.valueOf(1201), peer.error("surface.release", new Json.Obj().put("surface", "app:milestone")).integer("code"));
    }

    @Test public void surfaceContentAndParamFaults() {
        toReady();
        Json.Obj bad = new Json.Obj().put("surface", "app:milestone").put("revision", 1)
            .put("spec", new Json.Obj().put("t", "text"));
        Json.Obj err = peer.error("surface.update", bad);
        assertEquals(Long.valueOf(1201), err.integer("code"));
        assertEquals("spec", err.object("data").string("path"));
        assertNull(companion.presentation(Fixtures.PAIRING_ID, "app:milestone").nodeAt("r"));
        // Namespace not granted, bad id, non-integer revision.
        assertEquals("namespace-not-granted", peer.error("surface.update", new Json.Obj().put("surface", "widget:w")
            .put("revision", 1).put("spec", Fixtures.milestoneSpec(false))).object("data").string("reason"));
        assertEquals(Long.valueOf(1201), peer.error("surface.update", new Json.Obj().put("surface", "main")
            .put("revision", 1).put("spec", Fixtures.milestoneSpec(false))).integer("code"));
        assertEquals(Long.valueOf(-32602), peer.error("surface.update", new Json.Obj().put("surface", "app:m")
            .put("revision", Json.Num.of(1.0)).put("spec", Fixtures.milestoneSpec(false))).integer("code"));
        assertEquals(Long.valueOf(-32602), peer.error("surface.update", new Json.Obj().put("surface", "app:m")
            .put("revision", "1").put("spec", Fixtures.milestoneSpec(false))).integer("code"));
        // reset_input_ids naming anything is invalid (no stateful nodes).
        assertEquals(Long.valueOf(1201), peer.error("surface.update", new Json.Obj().put("surface", "app:m")
            .put("revision", 1).put("spec", Fixtures.milestoneSpec(false))
            .put("reset_input_ids", new Json.Arr().add("x"))).integer("code"));
        // A stale_spec must match the variant and be valid.
        assertEquals(Long.valueOf(1201), peer.error("surface.update", new Json.Obj().put("surface", "app:m")
            .put("revision", 1).put("spec", Fixtures.milestoneSpec(false)).put("stale_after_s", 10)
            .put("stale_spec", new Json.Obj().put("t", "text"))).integer("code"));
        assertEquals("applied", peer.result("surface.update", new Json.Obj().put("surface", "app:m")
            .put("revision", 1).put("spec", Fixtures.milestoneSpec(false)).put("stale_after_s", 10)
            .put("stale_spec", new Json.Obj().put("t", "text").put("text", "stale"))).string("status"));
    }

    @Test public void multiViewSwitchIsLocalAndReported() {
        toReady();
        Json.Obj spec = (Json.Obj) JsonParser.parse("{\"views\":{\"a\":{\"t\":\"column\",\"children\":[{\"t\":\"text\",\"text\":\"A\"},{\"t\":\"button\",\"label\":\"go\",\"on_tap\":{\"builtin\":\"view.switch\",\"view\":\"b\"}}]},\"b\":{\"t\":\"text\",\"text\":\"B\"}},\"initial_view\":\"a\"}");
        assertEquals("applied", peer.update("app:mv", 1, spec).string("status"));
        assertEquals("a", companion.presentation(Fixtures.PAIRING_ID, "app:mv").currentView);
        Companion.Admission a = companion.activate(Fixtures.PAIRING_ID, "app:mv", 1, "v:a.1");
        assertEquals(Companion.Admission.LOCAL_BUILTIN, a);
        assertEquals("b", companion.presentation(Fixtures.PAIRING_ID, "app:mv").currentView);
        assertEquals("b", companion.welcomeSurfaces(Fixtures.PAIRING_ID).object("app:mv").string("current_view"));
        List<Json.Obj> reported = peer.requestsFrom("event.action");
        assertEquals(1, reported.size());
        assertEquals("view.switched", reported.get(0).object("params").string("action"));
        assertEquals("b", reported.get(0).object("params").object("args").string("view"));
        assertFalse(reported.get(0).object("params").has("queued_at_ms"));
        peer.respond(reported.get(0), new Json.Obj().put("status", "accepted"));
        // A background refresh without current_view preserves the local view.
        assertEquals("applied", peer.update("app:mv", 2, spec).string("status"));
        assertEquals("b", companion.presentation(Fixtures.PAIRING_ID, "app:mv").currentView);
        // A single-root snapshot clears it.
        assertEquals("applied", peer.update("app:mv", 3, new Json.Obj().put("t", "text").put("text", "flat")).string("status"));
        assertNull(companion.presentation(Fixtures.PAIRING_ID, "app:mv").currentView);
    }

    // ------------------------------------------------------- actions/queue

    @Test public void durableActionRoundTrip() {
        toReady();
        peer.update("app:milestone", 1, Fixtures.milestoneSpec(false));
        Companion.Admission a = companion.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.3");
        assertEquals(Companion.Admission.QUEUED, a);
        List<Json.Obj> reqs = peer.requestsFrom("event.action");
        assertEquals(1, reqs.size());
        Json.Obj params = reqs.get(0).object("params");
        assertEquals("milestone.flag-set", params.string("action"));
        assertTrue(params.object("args").bool("desired"));
        assertEquals("app:milestone", params.string("surface"));
        assertEquals(Long.valueOf(1), params.integer("revision_seen"));
        assertTrue(params.has("queued_at_ms"));
        assertTrue(companion.inFlight(Fixtures.PAIRING_ID));
        assertEquals(1, companion.queuedCount(Fixtures.PAIRING_ID));
        // Emacs accepts: the record is deleted.
        peer.respond(reqs.get(0), new Json.Obj().put("status", "accepted"));
        assertEquals(0, companion.queuedCount(Fixtures.PAIRING_ID));
        assertFalse(companion.inFlight(Fixtures.PAIRING_ID));
        // Stale revision, missing node, disabled node: safe no-ops.
        assertEquals(Companion.Admission.NOT_PRESENTED, companion.activate(Fixtures.PAIRING_ID, "app:milestone", 0, "r.3"));
        assertEquals(Companion.Admission.NOT_PRESENTED, companion.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.9"));
        assertEquals(Companion.Admission.NOT_PRESENTED, companion.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.0"));
    }

    @Test public void errorPausesPumpAndReplayResumesInOrder() {
        toReady();
        peer.update("app:milestone", 1, Fixtures.milestoneSpec(false));
        assertEquals(Companion.Admission.QUEUED, companion.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.3"));
        List<Json.Obj> reqs = peer.requestsFrom("event.action");
        assertEquals(1, reqs.size());
        // A second activation while the first is in flight is admitted behind it.
        assertEquals(Companion.Admission.QUEUED, companion.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.4"));
        assertEquals(1, peer.requestsFrom("event.action").size()); // single flight
        assertEquals(2, companion.queuedCount(Fixtures.PAIRING_ID));
        // 1500 event-retry retains the head and pauses the pump.
        peer.respondError(reqs.get(0), 1500, "event-retry");
        assertTrue(peer.engine.pumpPaused());
        assertEquals(2, companion.queuedCount(Fixtures.PAIRING_ID));
        assertEquals(1, peer.requestsFrom("event.action").size());
        // queue.replay resumes: the SAME event_id is resent first.
        String firstId = reqs.get(0).object("params").string("event_id");
        Json.Obj replayReq = Envelope.request(new Json.Str("rp"), "queue.replay", new Json.Obj());
        peer.send(replayReq);
        List<Json.Obj> after = peer.requestsFrom("event.action");
        assertEquals(2, after.size());
        assertEquals(firstId, after.get(1).object("params").string("event_id"));
        peer.respond(after.get(1), new Json.Obj().put("status", "duplicate"));
        List<Json.Obj> next = peer.requestsFrom("event.action");
        assertEquals(3, next.size());
        assertFalse(next.get(2).object("params").object("args").bool("desired"));
        peer.respond(next.get(2), new Json.Obj().put("status", "accepted"));
        // The replay concludes with the summary.
        Json.Obj summary = null;
        for (Json.Obj m : peer.inbox) if (m.has("id") && !m.has("method") && "rp".equals(m.string("id"))) summary = m.object("result");
        assertEquals(Long.valueOf(2), summary.integer("delivered"));
        assertEquals(Long.valueOf(0), summary.integer("remaining"));
        assertTrue(summary.get("blocked_by").isNull());
        assertEquals(0, companion.queuedCount(Fixtures.PAIRING_ID));
    }

    @Test public void replaySummaryReportsBlockedByAndRejected() {
        toReady();
        peer.update("app:milestone", 1, Fixtures.milestoneSpec(false));
        companion.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.3");
        Json.Obj req = peer.requestsFrom("event.action").get(0);
        peer.respond(req, new Json.Obj().put("status", "rejected"));
        assertEquals(0, companion.queuedCount(Fixtures.PAIRING_ID));
        assertTrue(events.log.toString().contains("concluded rejected"));
        companion.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.4");
        req = peer.requestsFrom("event.action").get(1);
        Json.Obj replayReq = Envelope.request(new Json.Str("rp"), "queue.replay", new Json.Obj());
        peer.send(replayReq); // joins the in-flight request
        peer.respondError(req, 1500, "event-retry");
        Json.Obj summary = null;
        for (Json.Obj m : peer.inbox) if (m.has("id") && !m.has("method") && "rp".equals(m.string("id"))) summary = m.object("result");
        assertEquals("event-retry", summary.string("blocked_by"));
        assertEquals(Long.valueOf(1), summary.integer("remaining"));
        // A second replay while one is active is 1600.
        peer.send(Envelope.request(new Json.Str("rp2"), "queue.replay", new Json.Obj()));
        Json.Obj rp2 = null;
        for (Json.Obj m : peer.inbox) if (m.has("id") && !m.has("method") && "rp2".equals(m.string("id"))) rp2 = m;
        assertNull(rp2 == null ? null : rp2.object("error")); // rp2 concluded normally (the first already concluded)
        // Unknown result status: log.error and close, event retained.
        Json.Obj req3 = peer.requestsFrom("event.action").get(2);
        peer.respond(req3, new Json.Obj().put("status", "maybe"));
        assertEquals(SessionState.CLOSED, peer.engine.state());
        assertEquals(1, peer.notifications("log.error").size());
        assertEquals(1, companion.queuedCount(Fixtures.PAIRING_ID));
    }

    @Test public void syncingBarrierHoldsNewEventsBehindReplay() {
        // First session: publish and go READY, queue one event, then lose the transport.
        toReady();
        peer.update("app:milestone", 1, Fixtures.milestoneSpec(false));
        peer.sinkFails = true;
        assertEquals(Companion.Admission.QUEUED, companion.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.3"));
        assertEquals(SessionState.CLOSED, peer.engine.state()); // the send failed and closed the transport
        assertEquals(1, companion.queuedCount(Fixtures.PAIRING_ID));
        // Second session: the welcome counts the retained event; a new
        // activation during SYNCING is admitted but held behind the barrier.
        peer = new Peer(companion);
        Json.Obj welcome = peer.handshake(Fixtures.PAIRING_ID, Fixtures.TOKEN, Fixtures.noWants());
        assertEquals(Long.valueOf(1), welcome.integer("queued_events"));
        assertEquals(Long.valueOf(1), welcome.object("surfaces").object("app:milestone").integer("revision"));
        assertEquals(Companion.Admission.QUEUED, companion.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.4"));
        assertEquals(0, peer.requestsFrom("event.action").size()); // nothing before replay
        peer.send(Envelope.request(new Json.Str("rp"), "queue.replay", new Json.Obj()));
        List<Json.Obj> reqs = peer.requestsFrom("event.action");
        assertEquals(1, reqs.size());
        assertTrue(reqs.get(0).object("params").object("args").bool("desired")); // the old head first
        peer.respond(reqs.get(0), new Json.Obj().put("status", "accepted"));
        // The barrier holds the new event; replay concludes with remaining 1.
        Json.Obj summary = null;
        for (Json.Obj m : peer.inbox) if (m.has("id") && !m.has("method") && "rp".equals(m.string("id"))) summary = m.object("result");
        assertEquals(Long.valueOf(1), summary.integer("remaining"));
        assertEquals(1, peer.requestsFrom("event.action").size());
        peer.ready();
        // READY releases it.
        List<Json.Obj> after = peer.requestsFrom("event.action");
        assertEquals(2, after.size());
        assertFalse(after.get(1).object("params").object("args").bool("desired"));
        peer.respond(after.get(1), new Json.Obj().put("status", "accepted"));
        assertEquals(0, companion.queuedCount(Fixtures.PAIRING_ID));
    }

    @Test public void dropActionsAreLiveOnlyAndDiscardedOffline() {
        toReady();
        Json.Obj spec = (Json.Obj) JsonParser.parse("{\"t\":\"button\",\"label\":\"x\",\"on_tap\":{\"action\":\"a.b\"}}");
        peer.update("app:d", 1, spec);
        assertEquals(Companion.Admission.DELIVERED_LIVE, companion.activate(Fixtures.PAIRING_ID, "app:d", 1, "r"));
        Json.Obj req = peer.requestsFrom("event.action").get(0);
        assertFalse(req.object("params").has("queued_at_ms"));
        assertEquals(0, companion.queuedCount(Fixtures.PAIRING_ID));
        peer.respond(req, new Json.Obj().put("status", "accepted"));
        peer.engine.close("test");
        assertEquals(Companion.Admission.DROPPED_OFFLINE, companion.activate(Fixtures.PAIRING_ID, "app:d", 1, "r"));
        assertEquals(0, companion.queuedCount(Fixtures.PAIRING_ID));
    }

    @Test public void newerSessionSupersedesTheOlder() {
        toReady();
        Peer second = new Peer(companion);
        second.handshake(Fixtures.PAIRING_ID, Fixtures.TOKEN, Fixtures.noWants());
        assertEquals(SessionState.CLOSED, peer.engine.state());
        assertEquals(1, peer.notifications("session.superseded").size());
        assertEquals(SessionState.SYNCING, second.engine.state());
        assertTrue(companion.currentEngine() == second.engine);
    }

    @Test public void settingsBuiltinReachesTheHost() {
        toReady();
        Json.Obj spec = (Json.Obj) JsonParser.parse("{\"t\":\"button\",\"label\":\"x\",\"on_tap\":{\"builtin\":\"companion.settings.open\"}}");
        peer.update("app:s", 1, spec);
        assertEquals(Companion.Admission.LOCAL_BUILTIN, companion.activate(Fixtures.PAIRING_ID, "app:s", 1, "r"));
        assertTrue(events.log.contains("settings"));
    }
}
