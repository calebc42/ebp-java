// SPDX-License-Identifier: GPL-3.0-or-later
// The Android durable store: one SQLite database in the application's
// private files directory, WAL journal, synchronous=FULL, every write one
// explicit transaction that commits before `write` returns.  Schema
// version 1 is stamped in `user_version`; any other version fails closed.
// Compiled only inside the Emacs Android tree (android.jar); the core
// never references it.
package com.calebc42.ebp.companion.android;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.json.JsonParser;
import com.calebc42.ebp.companion.store.CompanionStore;
import com.calebc42.ebp.companion.store.Counters;
import com.calebc42.ebp.companion.store.IssuedEventId;
import com.calebc42.ebp.companion.store.OutboxRecord;
import com.calebc42.ebp.companion.store.Snapshot;
import com.calebc42.ebp.companion.store.StoreException;
import com.calebc42.ebp.companion.store.SurfaceRecord;
import com.calebc42.ebp.companion.store.WriteTx;

import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class AndroidSqliteStore implements CompanionStore {

    public static final int SCHEMA_VERSION = 1;

    private final SQLiteDatabase db;

    public AndroidSqliteStore(File file) throws StoreException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new StoreException("cannot create " + dir);
        try {
            db = SQLiteDatabase.openDatabase(file.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY);
            db.enableWriteAheadLogging();
            db.execSQL("PRAGMA synchronous = FULL");
            db.execSQL("PRAGMA foreign_keys = ON");
            int version = db.getVersion();
            if (version == 0) {
                db.beginTransaction();
                try {
                    db.execSQL("CREATE TABLE partitions (pairing_id TEXT PRIMARY KEY NOT NULL,"
                        + " next_queue_seq INTEGER NOT NULL, next_surface_ordinal INTEGER NOT NULL,"
                        + " clock_high_water_ms INTEGER NOT NULL, ready_disconnected_at_ms INTEGER)");
                    db.execSQL("CREATE TABLE surfaces (pairing_id TEXT NOT NULL, surface TEXT NOT NULL,"
                        + " revision INTEGER NOT NULL, present INTEGER NOT NULL, spec TEXT, stale_spec TEXT,"
                        + " stale_after_s INTEGER, current_view TEXT, accepted_at_ms INTEGER NOT NULL,"
                        + " first_seen_ordinal INTEGER NOT NULL, PRIMARY KEY (pairing_id, surface))");
                    db.execSQL("CREATE TABLE outbox (pairing_id TEXT NOT NULL, queue_seq INTEGER NOT NULL,"
                        + " event_id TEXT NOT NULL, event TEXT NOT NULL, policy TEXT NOT NULL, dedupe TEXT,"
                        + " occurred_at_ms INTEGER NOT NULL, queued_at_ms INTEGER NOT NULL,"
                        + " expires_at_ms INTEGER NOT NULL, accounted_bytes INTEGER NOT NULL,"
                        + " PRIMARY KEY (pairing_id, queue_seq))");
                    db.execSQL("CREATE TABLE issued_event_ids (pairing_id TEXT NOT NULL, event_id TEXT NOT NULL,"
                        + " reusable_after_ms INTEGER NOT NULL, PRIMARY KEY (pairing_id, event_id))");
                    db.setVersion(SCHEMA_VERSION);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } else if (version != SCHEMA_VERSION) {
                db.close();
                throw new StoreException("unsupported companion store schema " + version);
            }
        } catch (SQLException e) {
            throw new StoreException("cannot open companion store", e);
        }
    }

    @Override public synchronized Snapshot load(String pairingId) throws StoreException {
        try {
            Tx tx = new Tx(pairingId);
            return new Snapshot(tx.counters(), tx.surfaces(), tx.outbox(), tx.issuedEventIds());
        } catch (SQLException e) {
            throw new StoreException("load failed", e);
        }
    }

    @Override public synchronized <T> T write(String pairingId, WriteBlock<T> block) throws StoreException {
        try {
            db.beginTransaction();
        } catch (SQLException e) {
            throw new StoreException("BEGIN failed", e);
        }
        boolean committed = false;
        try {
            T result = block.apply(new Tx(pairingId));
            db.setTransactionSuccessful();
            committed = true;
            return result;
        } catch (SQLException e) {
            throw new StoreException("transaction failed", e);
        } finally {
            try {
                db.endTransaction();
            } catch (SQLException e) {
                if (committed) throw new StoreException("COMMIT failed", e);
            }
        }
    }

    @Override public synchronized void erasePairing(final String pairingId) throws StoreException {
        write(pairingId, new WriteBlock<Void>() {
            @Override public Void apply(WriteTx tx) {
                String[] args = {pairingId};
                db.execSQL("DELETE FROM issued_event_ids WHERE pairing_id = ?", args);
                db.execSQL("DELETE FROM outbox WHERE pairing_id = ?", args);
                db.execSQL("DELETE FROM surfaces WHERE pairing_id = ?", args);
                db.execSQL("DELETE FROM partitions WHERE pairing_id = ?", args);
                return null;
            }
        });
    }

    @Override public synchronized void close() { db.close(); }

    private static Json.Obj parseStored(String text) throws StoreException {
        if (text == null) return null;
        try {
            Json.Value v = JsonParser.parse(text, 512);
            if (!(v instanceof Json.Obj)) throw new StoreException("corrupt stored document");
            return (Json.Obj) v;
        } catch (RuntimeException e) {
            throw new StoreException("corrupt stored document", e);
        }
    }

    private final class Tx implements WriteTx {
        private final String pairingId;
        Tx(String pairingId) { this.pairingId = pairingId; }

        @Override public Counters counters() throws StoreException {
            Cursor c = db.rawQuery("SELECT next_queue_seq, next_surface_ordinal, clock_high_water_ms,"
                + " ready_disconnected_at_ms FROM partitions WHERE pairing_id = ?", new String[] {pairingId});
            try {
                if (!c.moveToFirst()) return Counters.initial();
                return new Counters(c.getLong(0), c.getLong(1), c.getLong(2),
                    c.isNull(3) ? null : Long.valueOf(c.getLong(3)));
            } finally {
                c.close();
            }
        }

        @Override public void putCounters(Counters counters) {
            db.execSQL("INSERT OR REPLACE INTO partitions (pairing_id, next_queue_seq, next_surface_ordinal,"
                + " clock_high_water_ms, ready_disconnected_at_ms) VALUES (?, ?, ?, ?, ?)",
                new Object[] {pairingId, Long.valueOf(counters.nextQueueSeq), Long.valueOf(counters.nextSurfaceOrdinal),
                    Long.valueOf(counters.clockHighWaterMs), counters.readyDisconnectedAtMs});
        }

        private SurfaceRecord row(Cursor c) throws StoreException {
            boolean present = c.getLong(2) != 0;
            return new SurfaceRecord(c.getString(0), c.getLong(1), present,
                present ? parseStored(c.getString(3)) : null,
                present ? parseStored(c.getString(4)) : null,
                present && !c.isNull(5) ? Long.valueOf(c.getLong(5)) : null,
                present ? c.getString(6) : null, c.getLong(7), c.getLong(8));
        }

        private static final String SURFACE_COLUMNS = "surface, revision, present, spec, stale_spec,"
            + " stale_after_s, current_view, accepted_at_ms, first_seen_ordinal";

        @Override public SurfaceRecord surface(String surfaceId) throws StoreException {
            Cursor c = db.rawQuery("SELECT " + SURFACE_COLUMNS + " FROM surfaces WHERE pairing_id = ? AND surface = ?",
                new String[] {pairingId, surfaceId});
            try {
                return c.moveToFirst() ? row(c) : null;
            } finally {
                c.close();
            }
        }

        @Override public List<SurfaceRecord> surfaces() throws StoreException {
            Cursor c = db.rawQuery("SELECT " + SURFACE_COLUMNS + " FROM surfaces WHERE pairing_id = ?"
                + " ORDER BY first_seen_ordinal", new String[] {pairingId});
            try {
                List<SurfaceRecord> out = new ArrayList<SurfaceRecord>();
                while (c.moveToNext()) out.add(row(c));
                return out;
            } finally {
                c.close();
            }
        }

        @Override public void putSurface(SurfaceRecord r) {
            db.execSQL("INSERT OR REPLACE INTO surfaces (pairing_id, surface, revision, present, spec, stale_spec,"
                + " stale_after_s, current_view, accepted_at_ms, first_seen_ordinal) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[] {pairingId, r.surface, Long.valueOf(r.revision), Integer.valueOf(r.present ? 1 : 0),
                    r.spec == null ? null : Json.write(r.spec), r.staleSpec == null ? null : Json.write(r.staleSpec),
                    r.staleAfterS, r.currentView, Long.valueOf(r.acceptedAtMs), Long.valueOf(r.firstSeenOrdinal)});
        }

        @Override public void deleteSurface(String surfaceId) {
            db.execSQL("DELETE FROM surfaces WHERE pairing_id = ? AND surface = ?", new Object[] {pairingId, surfaceId});
        }

        @Override public List<OutboxRecord> outbox() throws StoreException {
            Cursor c = db.rawQuery("SELECT queue_seq, event_id, event, policy, dedupe, occurred_at_ms, queued_at_ms,"
                + " expires_at_ms, accounted_bytes FROM outbox WHERE pairing_id = ? ORDER BY queue_seq",
                new String[] {pairingId});
            try {
                List<OutboxRecord> out = new ArrayList<OutboxRecord>();
                while (c.moveToNext()) {
                    out.add(new OutboxRecord(c.getLong(0), c.getString(1), parseStored(c.getString(2)), c.getString(3),
                        c.isNull(4) ? null : c.getString(4), c.getLong(5), c.getLong(6), c.getLong(7), c.getLong(8)));
                }
                return out;
            } finally {
                c.close();
            }
        }

        @Override public void putOutbox(OutboxRecord r) {
            db.execSQL("INSERT OR REPLACE INTO outbox (pairing_id, queue_seq, event_id, event, policy, dedupe,"
                + " occurred_at_ms, queued_at_ms, expires_at_ms, accounted_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[] {pairingId, Long.valueOf(r.queueSeq), r.eventId, Json.write(r.event), r.policy, r.dedupe,
                    Long.valueOf(r.occurredAtMs), Long.valueOf(r.queuedAtMs), Long.valueOf(r.expiresAtMs),
                    Long.valueOf(r.accountedBytes)});
        }

        @Override public void deleteOutbox(long queueSeq) {
            db.execSQL("DELETE FROM outbox WHERE pairing_id = ? AND queue_seq = ?",
                new Object[] {pairingId, Long.valueOf(queueSeq)});
        }

        @Override public List<IssuedEventId> issuedEventIds() {
            Cursor c = db.rawQuery("SELECT event_id, reusable_after_ms FROM issued_event_ids WHERE pairing_id = ?",
                new String[] {pairingId});
            try {
                List<IssuedEventId> out = new ArrayList<IssuedEventId>();
                while (c.moveToNext()) out.add(new IssuedEventId(c.getString(0), c.getLong(1)));
                return out;
            } finally {
                c.close();
            }
        }

        @Override public void putIssuedEventId(IssuedEventId r) {
            db.execSQL("INSERT OR REPLACE INTO issued_event_ids (pairing_id, event_id, reusable_after_ms) VALUES (?, ?, ?)",
                new Object[] {pairingId, r.eventId, Long.valueOf(r.reusableAfterMs)});
        }

        @Override public void deleteIssuedEventId(String eventId) {
            db.execSQL("DELETE FROM issued_event_ids WHERE pairing_id = ? AND event_id = ?", new Object[] {pairingId, eventId});
        }
    }
}
