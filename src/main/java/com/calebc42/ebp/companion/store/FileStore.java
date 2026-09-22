// SPDX-License-Identifier: GPL-3.0-or-later
// One versioned JSON file per pairing partition with write-to-temp, fsync,
// atomic-rename replacement, so a crash leaves either the old state or the
// new state, never a torn one.  The host kill-matrix witness: process death
// is "re-open the same directory".
package com.calebc42.ebp.companion.store;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.json.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FileStore extends MemoryStore {

    public static final int FORMAT = 1;

    private final File directory;
    private final Map<String, Boolean> loaded = new HashMap<String, Boolean>();

    public FileStore(File directory) throws StoreException {
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new StoreException("cannot create store directory " + directory);
    }

    private File fileFor(String pairingId) { return new File(directory, pairingId + ".json"); }

    @Override protected synchronized Partition partition(String pairingId) {
        Partition p = super.partition(pairingId);
        if (!loaded.containsKey(pairingId)) {
            try {
                loadInto(pairingId, p);
            } catch (StoreException e) {
                throw new IllegalStateException(e);
            }
            loaded.put(pairingId, Boolean.TRUE);
        }
        return p;
    }

    @Override public synchronized Snapshot load(String pairingId) throws StoreException {
        try {
            return super.load(pairingId);
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof StoreException) throw (StoreException) e.getCause();
            throw e;
        }
    }

    @Override public synchronized <T> T write(String pairingId, WriteBlock<T> block) throws StoreException {
        try {
            return super.write(pairingId, block);
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof StoreException) throw (StoreException) e.getCause();
            throw e;
        }
    }

    private void loadInto(String pairingId, Partition p) throws StoreException {
        File file = fileFor(pairingId);
        if (!file.exists()) return;
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StoreException("cannot read " + file, e);
        }
        if (text.trim().isEmpty()) return;
        Json.Value root;
        try {
            // Persisted text is parsed with a larger nesting allowance than
            // the wire: a record wraps event args that may legally sit near
            // the wire's 64-container limit.
            root = JsonParser.parse(text, 512);
        } catch (RuntimeException e) {
            throw new StoreException("corrupt store file " + file, e);
        }
        if (!root.isObject()) throw new StoreException("corrupt store file " + file);
        Json.Obj o = root.asObject();
        Long format = o.integer("format");
        if (format == null || format.longValue() != FORMAT)
            throw new StoreException("unsupported store format " + format + " in " + file);
        Json.Obj c = o.object("counters");
        if (c == null) throw new StoreException("corrupt counters in " + file);
        p.counters = new Counters(req(c, "next_queue_seq"), req(c, "next_surface_ordinal"),
            req(c, "clock_high_water_ms"), c.integer("ready_disconnected_at_ms"));
        Json.Arr surfaces = o.array("surfaces");
        if (surfaces == null) throw new StoreException("corrupt surfaces in " + file);
        for (int i = 0; i < surfaces.size(); i++) {
            Json.Obj r = surfaces.get(i).asObject();
            boolean present = r.boolOr("present", false);
            SurfaceRecord record = new SurfaceRecord(r.string("surface"), req(r, "revision"), present,
                present ? r.object("spec") : null, present ? r.object("stale_spec") : null,
                present ? r.integer("stale_after_s") : null, present ? r.string("current_view") : null,
                req(r, "accepted_at_ms"), req(r, "first_seen_ordinal"));
            p.surfaces.put(record.surface, record);
        }
        Json.Arr outbox = o.array("outbox");
        if (outbox == null) throw new StoreException("corrupt outbox in " + file);
        for (int i = 0; i < outbox.size(); i++) {
            Json.Obj r = outbox.get(i).asObject();
            OutboxRecord record = new OutboxRecord(req(r, "queue_seq"), r.string("event_id"),
                r.object("event"), r.string("policy"), r.string("dedupe"), req(r, "occurred_at_ms"),
                req(r, "queued_at_ms"), req(r, "expires_at_ms"), req(r, "accounted_bytes"));
            p.outbox.put(Long.valueOf(record.queueSeq), record);
        }
        Json.Arr issued = o.array("issued_event_ids");
        if (issued != null) {
            for (int i = 0; i < issued.size(); i++) {
                Json.Obj r = issued.get(i).asObject();
                IssuedEventId record = new IssuedEventId(r.string("event_id"), req(r, "reusable_after_ms"));
                p.issued.put(record.eventId, record);
            }
        }
    }

    private static long req(Json.Obj o, String key) throws StoreException {
        Long v = o.integer(key);
        if (v == null) throw new StoreException("corrupt store: missing " + key);
        return v.longValue();
    }

    @Override protected void commit(String pairingId, Partition working) throws StoreException {
        Json.Obj root = new Json.Obj().put("format", FORMAT);
        Counters c = working.counters;
        Json.Obj counters = new Json.Obj().put("next_queue_seq", c.nextQueueSeq)
            .put("next_surface_ordinal", c.nextSurfaceOrdinal)
            .put("clock_high_water_ms", c.clockHighWaterMs);
        if (c.readyDisconnectedAtMs != null)
            counters.put("ready_disconnected_at_ms", c.readyDisconnectedAtMs.longValue());
        root.put("counters", counters);
        Json.Arr surfaces = new Json.Arr();
        for (SurfaceRecord r : working.snapshot().surfaces) {
            Json.Obj s = new Json.Obj().put("surface", r.surface).put("revision", r.revision)
                .put("present", r.present).put("accepted_at_ms", r.acceptedAtMs)
                .put("first_seen_ordinal", r.firstSeenOrdinal);
            if (r.present) {
                s.put("spec", r.spec);
                if (r.staleSpec != null) s.put("stale_spec", r.staleSpec);
                if (r.staleAfterS != null) s.put("stale_after_s", r.staleAfterS.longValue());
                if (r.currentView != null) s.put("current_view", r.currentView);
            }
            surfaces.add(s);
        }
        root.put("surfaces", surfaces);
        Json.Arr outbox = new Json.Arr();
        for (OutboxRecord r : sortedOutbox(working.outbox)) {
            Json.Obj s = new Json.Obj().put("queue_seq", r.queueSeq).put("event_id", r.eventId)
                .put("event", r.event).put("policy", r.policy)
                .put("occurred_at_ms", r.occurredAtMs).put("queued_at_ms", r.queuedAtMs)
                .put("expires_at_ms", r.expiresAtMs).put("accounted_bytes", r.accountedBytes);
            if (r.dedupe != null) s.put("dedupe", r.dedupe);
            outbox.add(s);
        }
        root.put("outbox", outbox);
        Json.Arr issued = new Json.Arr();
        List<IssuedEventId> ids = new ArrayList<IssuedEventId>(working.issued.values());
        for (IssuedEventId r : ids)
            issued.add(new Json.Obj().put("event_id", r.eventId).put("reusable_after_ms", r.reusableAfterMs));
        root.put("issued_event_ids", issued);
        atomicReplace(fileFor(pairingId), Json.writeUtf8(root));
    }

    private void atomicReplace(File file, byte[] content) throws StoreException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(content);
                out.getFD().sync();
            } finally {
                out.close();
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            // Durability of the rename itself needs a directory fsync on
            // POSIX; best-effort — the JVM cannot demand it portably.
            try {
                FileChannel dir = FileChannel.open(file.getParentFile().toPath(), StandardOpenOption.READ);
                try { dir.force(true); } finally { dir.close(); }
            } catch (IOException ignored) {
                // best effort
            }
        } catch (IOException e) {
            temp.delete();
            throw new StoreException("atomic replace failed for " + file, e);
        }
    }
}
