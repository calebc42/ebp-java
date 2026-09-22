// SPDX-License-Identifier: GPL-3.0-or-later
// Deterministic test-only interruption points at the named commit/response
// boundaries the acceptance matrix tests.  Nothing arms a failpoint unless
// a host harness (or a debuggable Android build's diagnostics entry) calls
// arm(); production paths never do, and no protocol traffic can.
package com.calebc42.ebp.companion.engine;

import java.util.HashSet;
import java.util.Set;

public final class Failpoints {
    private Failpoints() {}

    /** Thrown when an armed failpoint is reached.  A host harness treats it
     * as process death at that boundary (Runtime.halt), which is exactly the
     * observation the kill matrix needs. */
    public static final class Tripped extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String name;
        Tripped(String name) { super("failpoint tripped: " + name); this.name = name; }
    }

    public static final String SURFACE_BEFORE_COMMIT = "surface.before-commit";
    public static final String SURFACE_AFTER_COMMIT_BEFORE_REPLY = "surface.after-commit-before-reply";
    public static final String OUTBOX_BEFORE_COMMIT = "outbox.before-commit";
    public static final String OUTBOX_AFTER_COMMIT_BEFORE_SEND = "outbox.after-commit-before-send";
    public static final String EVENT_AFTER_RESULT_BEFORE_DELETE = "event.after-result-before-delete";

    private static final Set<String> KNOWN = new HashSet<String>();
    private static final Set<String> ARMED = new HashSet<String>();
    private static volatile boolean enabled = false;

    static {
        KNOWN.add(SURFACE_BEFORE_COMMIT);
        KNOWN.add(SURFACE_AFTER_COMMIT_BEFORE_REPLY);
        KNOWN.add(OUTBOX_BEFORE_COMMIT);
        KNOWN.add(OUTBOX_AFTER_COMMIT_BEFORE_SEND);
        KNOWN.add(EVENT_AFTER_RESULT_BEFORE_DELETE);
    }

    public static boolean isKnown(String name) { return KNOWN.contains(name); }

    /** Arm NAME to trip exactly once.  Returns false for an unknown name. */
    public static synchronized boolean arm(String name) {
        if (!KNOWN.contains(name)) return false;
        ARMED.add(name);
        enabled = true;
        return true;
    }

    public static synchronized void disarmAll() { ARMED.clear(); enabled = false; }

    /** Called at each boundary; a no-op unless NAME is armed. */
    public static void hit(String name) {
        if (!enabled) return;
        synchronized (Failpoints.class) {
            if (!ARMED.remove(name)) return;
            if (ARMED.isEmpty()) enabled = false;
        }
        throw new Tripped(name);
    }
}
