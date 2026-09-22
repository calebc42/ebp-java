// SPDX-License-Identifier: GPL-3.0-or-later
// The kill matrix on the file store: deterministic failpoints at the named
// commit/response boundaries, then "process death" = a fresh Companion over
// the same directory.  Also queue expiry under clock rollback, dedupe,
// capacity, and EventId non-reuse (SPEC 24.6 items 8-10).
package com.calebc42.ebp.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.DurableQueue;
import com.calebc42.ebp.companion.engine.Failpoints;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.json.JsonParser;
import com.calebc42.ebp.companion.rpc.Envelope;
import com.calebc42.ebp.companion.store.FileStore;
import com.calebc42.ebp.companion.store.Snapshot;
import com.calebc42.ebp.companion.store.StoreException;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RecoveryTest {
    private File dir;
    private Fixtures.TestClock clock;
    private Fixtures.Events events;

    @Before public void setUp() throws Exception {
        dir = Files.createTempDirectory("ebp-recovery").toFile();
        clock = new Fixtures.TestClock();
        events = new Fixtures.Events();
        Failpoints.disarmAll();
    }

    @After public void tearDown() {
        Failpoints.disarmAll();
        deleteTree(dir);
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    /** A fresh process: new store handle, new Companion, new session. */
    private Companion reopen() throws StoreException {
        return Fixtures.companion(new FileStore(dir), clock, events);
    }

    private Peer readyPeer(Companion c) {
        Peer p = new Peer(c);
        p.handshake(Fixtures.PAIRING_ID, Fixtures.TOKEN, Fixtures.noWants());
        p.replay();
        p.ready();
        return p;
    }

    private Snapshot durable() throws StoreException {
        return new FileStore(dir).load(Fixtures.PAIRING_ID);
    }

    @Test public void crashAfterOutboxCommitBeforeSend() throws Exception {
        Companion c = reopen();
        Peer p = readyPeer(c);
        p.update("app:milestone", 1, Fixtures.milestoneSpec(false));
        Failpoints.arm(Failpoints.OUTBOX_AFTER_COMMIT_BEFORE_SEND);
        try {
            c.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.3");
            fail("failpoint did not trip");
        } catch (Failpoints.Tripped expected) { /* process death here */ }
        assertEquals(0, p.requestsFrom("event.action").size());
        Snapshot s = durable();
        assertEquals(1, s.outbox.size());
        String eventId = s.outbox.get(0).eventId;
        // Relaunch: the welcome reports it, replay delivers the SAME EventId.
        Companion c2 = reopen();
        Peer p2 = new Peer(c2);
        Json.Obj welcome = p2.handshake(Fixtures.PAIRING_ID, Fixtures.TOKEN, Fixtures.noWants());
        assertEquals(Long.valueOf(1), welcome.integer("queued_events"));
        p2.send(Envelope.request(new Json.Str("rp"), "queue.replay", new Json.Obj()));
        List<Json.Obj> reqs = p2.requestsFrom("event.action");
        assertEquals(1, reqs.size());
        assertEquals(eventId, reqs.get(0).object("params").string("event_id"));
        p2.respond(reqs.get(0), new Json.Obj().put("status", "accepted"));
        assertEquals(0, durable().outbox.size());
        assertEquals(1, durable().issuedEventIds.size()); // the id stays reserved
    }

    @Test public void crashAfterResultBeforeDeleteRedeliversAndEmacsAnswersDuplicate() throws Exception {
        Companion c = reopen();
        Peer p = readyPeer(c);
        p.update("app:milestone", 1, Fixtures.milestoneSpec(false));
        c.activate(Fixtures.PAIRING_ID, "app:milestone", 1, "r.3");
        Json.Obj req = p.requestsFrom("event.action").get(0);
        String eventId = req.object("params").string("event_id");
        Failpoints.arm(Failpoints.EVENT_AFTER_RESULT_BEFORE_DELETE);
        try {
            p.respond(req, new Json.Obj().put("status", "accepted"));
            fail("failpoint did not trip");
        } catch (Failpoints.Tripped expected) { /* process death after Emacs committed */ }
        assertEquals(1, durable().outbox.size());
        // Relaunch: the SYNCING replay redelivers it; Emacs, which already
        // committed the receipt, answers duplicate; only then does the
        // replay conclude and the barrier lift.
        Companion c2 = reopen();
        Peer p2 = new Peer(c2);
        p2.handshake(Fixtures.PAIRING_ID, Fixtures.TOKEN, Fixtures.noWants());
        p2.send(Envelope.request(new Json.Str("rp"), "queue.replay", new Json.Obj()));
        List<Json.Obj> reqs = p2.requestsFrom("event.action");
        assertEquals(1, reqs.size());
        assertEquals(eventId, reqs.get(0).object("params").string("event_id"));
        p2.respond(reqs.get(0), new Json.Obj().put("status", "duplicate"));
        Json.Obj summary = null;
        for (Json.Obj m : p2.inbox) if (m.has("id") && !m.has("method") && "rp".equals(m.string("id"))) summary = m.object("result");
        assertEquals(Long.valueOf(1), summary.integer("delivered"));
        assertEquals(Long.valueOf(0), summary.integer("remaining"));
        p2.ready();
        assertEquals(0, durable().outbox.size());
    }

    @Test public void crashDuringSnapshotTransactionLeavesOldOrNewState() throws Exception {
        Companion c = reopen();
        Peer p = readyPeer(c);
        p.update("app:milestone", 1, Fixtures.milestoneSpec(false));
        Failpoints.arm(Failpoints.SURFACE_BEFORE_COMMIT);
        try {
            p.update("app:milestone", 2, Fixtures.milestoneSpec(true));
            fail("failpoint did not trip");
        } catch (Failpoints.Tripped expected) { /* death before commit */ }
        Snapshot s = durable();
        assertEquals(1, s.surfaces.size());
        assertEquals(1, s.surfaces.get(0).revision);
        assertEquals("Flag: off", s.surfaces.get(0).spec.array("children").get(1).asObject().string("text"));
        // Death after commit, before the reply: the new state is durable and
        // Emacs's resend is answered stale, never applied twice.
        Companion c2 = reopen();
        Peer p2 = readyPeer(c2);
        Failpoints.arm(Failpoints.SURFACE_AFTER_COMMIT_BEFORE_REPLY);
        try {
            p2.update("app:milestone", 2, Fixtures.milestoneSpec(true));
            fail("failpoint did not trip");
        } catch (Failpoints.Tripped expected) { /* death after commit */ }
        s = durable();
        assertEquals(2, s.surfaces.get(0).revision);
        assertEquals("Flag: on", s.surfaces.get(0).spec.array("children").get(1).asObject().string("text"));
        Companion c3 = reopen();
        Peer p3 = new Peer(c3);
        Json.Obj welcome = p3.handshake(Fixtures.PAIRING_ID, Fixtures.TOKEN, Fixtures.noWants());
        assertEquals(Long.valueOf(2), welcome.object("surfaces").object("app:milestone").integer("revision"));
        Json.Obj r = p3.update("app:milestone", 2, Fixtures.milestoneSpec(true));
        assertEquals("stale", r.string("status"));
        assertEquals(Long.valueOf(2), r.integer("revision"));
    }

    @Test public void wholeProcessDeathRestoresCacheOutboxAndSession() throws Exception {
        Companion c = reopen();
        Peer p = readyPeer(c);
        p.update("app:milestone", 4, Fixtures.milestoneSpec(true));
        p.sinkFails = true; // transport gone: the next admission cannot deliver
        c.activate(Fixtures.PAIRING_ID, "app:milestone", 4, "r.4");
        c.activate(Fixtures.PAIRING_ID, "app:milestone", 4, "r.3");
        assertEquals(2, durable().outbox.size());
        // "Kill": drop every reference; reopen the directory.
        Companion c2 = reopen();
        assertTrue(c2.presentation(Fixtures.PAIRING_ID, "app:milestone").present);
        assertEquals(4, c2.presentation(Fixtures.PAIRING_ID, "app:milestone").revision);
        assertEquals(2, c2.queuedCount(Fixtures.PAIRING_ID));
        assertFalse(c2.inFlight(Fixtures.PAIRING_ID)); // nothing is in flight after death
        Peer p2 = new Peer(c2);
        p2.handshake(Fixtures.PAIRING_ID, Fixtures.TOKEN, Fixtures.noWants());
        p2.send(Envelope.request(new Json.Str("rp"), "queue.replay", new Json.Obj()));
        List<Json.Obj> reqs = p2.requestsFrom("event.action");
        assertFalse(reqs.get(0).object("params").object("args").bool("desired")); // FIFO: set-off first
        p2.respond(reqs.get(0), new Json.Obj().put("status", "accepted"));
        reqs = p2.requestsFrom("event.action");
        assertTrue(reqs.get(1).object("params").object("args").bool("desired"));
        p2.respond(reqs.get(1), new Json.Obj().put("status", "accepted"));
        p2.ready();
        assertEquals(0, c2.queuedCount(Fixtures.PAIRING_ID));
    }

    @Test public void queueSemantics() throws Exception {
        DurableQueue q = new DurableQueue(new FileStore(dir), Fixtures.PAIRING_ID, 3, 8388608, clock);
        Json.Obj e1 = event("00000000000000000000000000000001", clock.wall);
        assertEquals(DurableQueue.AdmitStatus.ADMITTED, q.admit(e1, "queue", "k", 60).status);
        // Dedupe replaces an older queued event with the same key.
        Json.Obj e2 = event("00000000000000000000000000000002", clock.wall);
        assertEquals(DurableQueue.AdmitStatus.ADMITTED, q.admit(e2, "queue", "k", 60).status);
        assertEquals(1, q.count());
        assertEquals("00000000000000000000000000000002", q.events().get(0).eventId);
        // The in-flight head is protected from replacement.
        DurableQueue.Delivery d = q.beginDelivery(null);
        assertEquals(DurableQueue.DeliveryKind.READY, d.kind);
        Json.Obj e3 = event("00000000000000000000000000000003", clock.wall);
        assertEquals(DurableQueue.AdmitStatus.ADMITTED, q.admit(e3, "queue", "k", 60).status);
        assertEquals(2, q.count());
        // EventId reuse within retention is refused.
        assertEquals(DurableQueue.AdmitStatus.EVENT_ID_REUSED, q.admit(event("00000000000000000000000000000001", clock.wall), "queue", null, 60).status);
        // Capacity: max_events 3.
        assertEquals(DurableQueue.AdmitStatus.ADMITTED, q.admit(event("00000000000000000000000000000004", clock.wall), "queue", null, 60).status);
        assertEquals(DurableQueue.AdmitStatus.QUEUE_FULL, q.admit(event("00000000000000000000000000000005", clock.wall), "queue", null, 60).status);
        assertEquals(3, q.count());
        q.clearInFlight(Long.valueOf(d.record.queueSeq));
        // Expiry: rolling the wall clock back does not extend lifetimes;
        // genuine elapsed time expires them.
        clock.wall -= 3600000L; // backward step
        clock.mono += 59000L;   // 59 s genuinely elapsed: nothing expires yet
        assertEquals(0, q.sweepExpired());
        clock.advance(2000);
        assertEquals(3, q.sweepExpired()); // 61 s elapsed > ttl 60 s despite the rollback
        assertEquals(0, q.count());
        assertEquals(3, q.takeExpiredCount());
        // Reopen: counters survive; the next queue_seq continues.
        DurableQueue q2 = new DurableQueue(new FileStore(dir), Fixtures.PAIRING_ID, 3, 8388608, clock);
        assertEquals(q.boundarySeq(), q2.boundarySeq());
        assertTrue(q2.boundarySeq() >= 5);
    }

    @Test public void storeFormatIsVersionedAndFailsClosed() throws Exception {
        Companion c = reopen();
        Peer p = readyPeer(c);
        p.update("app:milestone", 1, Fixtures.milestoneSpec(false));
        File f = new File(dir, Fixtures.PAIRING_ID + ".json");
        Json.Obj root = (Json.Obj) JsonParser.parse(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
        assertEquals(Long.valueOf(FileStore.FORMAT), root.integer("format"));
        Files.write(f.toPath(), Json.writeUtf8(root.with("format", Json.Num.of(99))));
        try {
            new FileStore(dir).load(Fixtures.PAIRING_ID);
            fail("unknown format accepted");
        } catch (StoreException expected) { /* fail closed */ }
        Files.write(f.toPath(), "{not json".getBytes("UTF-8"));
        try {
            new FileStore(dir).load(Fixtures.PAIRING_ID);
            fail("corrupt file accepted");
        } catch (StoreException expected) { /* fail closed */ }
    }

    private static Json.Obj event(String id, long now) {
        return new Json.Obj().put("event_id", id).put("action", "a.b").put("surface", "app:x")
            .put("revision_seen", 1).put("occurred_at_ms", now).put("queued_at_ms", now);
    }
}
