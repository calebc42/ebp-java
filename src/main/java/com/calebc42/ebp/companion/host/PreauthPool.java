// SPDX-License-Identifier: GPL-3.0-or-later
// The SPEC 5.7 pre-authentication pool every binder profile owes: a bound
// on concurrent connections that have not completed the Section 9
// handshake, a deadline after which such a connection is closed, and
// newest-wins eviction when the bound is reached — a new dial evicts the
// oldest pre-authentication connection rather than being refused, so a
// legitimate dial always gets a handshake attempt even under local
// flooding.  Transport-neutral: entries are Closeables.
package com.calebc42.ebp.companion.host;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public final class PreauthPool {

    /** SPEC 5.7: the handshake deadline in milliseconds. */
    public static final long DEADLINE_MS = 10000L;
    /** The default bound; SPEC 5.7 requires a bound, not a value. */
    public static final int DEFAULT_MAX = 8;

    private final int max;
    private final long deadlineMs;
    private final Timer timer = new Timer("ebp-preauth", true);
    /** Insertion order is admission order: the oldest entry is first. */
    private final Map<Closeable, TimerTask> pending = new LinkedHashMap<Closeable, TimerTask>();
    private boolean shut;

    public PreauthPool(int max, long deadlineMs) {
        this.max = Math.max(1, max);
        this.deadlineMs = deadlineMs;
    }

    public PreauthPool() { this(DEFAULT_MAX, DEADLINE_MS); }

    /** Admit a freshly accepted SOCKET; the deadline clock starts now. */
    public void admit(final Closeable socket) {
        List<Closeable> evicted = new ArrayList<Closeable>();
        synchronized (this) {
            if (shut) { evicted.add(socket); }
            else {
                Iterator<Map.Entry<Closeable, TimerTask>> it = pending.entrySet().iterator();
                while (pending.size() >= max && it.hasNext()) {
                    Map.Entry<Closeable, TimerTask> oldest = it.next();
                    oldest.getValue().cancel();
                    evicted.add(oldest.getKey());
                    it.remove();
                }
                TimerTask task = new TimerTask() {
                    @Override public void run() { expire(socket); }
                };
                pending.put(socket, task);
                timer.schedule(task, deadlineMs);
            }
        }
        for (Closeable c : evicted) close(c);
    }

    /** SOCKET completed the handshake (or ended): it leaves the pool open. */
    public synchronized void remove(Closeable socket) {
        TimerTask task = pending.remove(socket);
        if (task != null) task.cancel();
    }

    public synchronized int size() { return pending.size(); }

    public synchronized boolean contains(Closeable socket) { return pending.containsKey(socket); }

    /** Close every pending connection and stop the deadline timer. */
    public void shutdown() {
        List<Closeable> all;
        synchronized (this) {
            shut = true;
            all = new ArrayList<Closeable>(pending.keySet());
            for (TimerTask t : pending.values()) t.cancel();
            pending.clear();
            timer.cancel();
        }
        for (Closeable c : all) close(c);
    }

    private void expire(Closeable socket) {
        boolean still;
        synchronized (this) {
            still = pending.remove(socket) != null;
        }
        if (still) close(socket);
    }

    private static void close(Closeable c) {
        try { c.close(); } catch (IOException ignored) { /* closing */ }
    }
}
