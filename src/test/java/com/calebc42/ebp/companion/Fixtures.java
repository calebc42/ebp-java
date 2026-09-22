// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion;

import com.calebc42.ebp.companion.auth.Auth;
import com.calebc42.ebp.companion.engine.Clock;
import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.CompanionConfig;
import com.calebc42.ebp.companion.engine.M1Profile;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.rpc.SessionState;
import com.calebc42.ebp.companion.store.CompanionStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Shared test scaffolding: the KAT pairing, a deterministic clock and
 * random source, an inline executor, and the milestone SurfaceSpec. */
final class Fixtures {
    static final String PAIRING_ID = "101112131415161718191a1b1c1d1e1f";
    static final String TOKEN_DISPLAY = "AAECAwQFBgcICQoLDA0ODw";
    static final byte[] TOKEN = Auth.decodePairingToken(TOKEN_DISPLAY);

    /** A controllable clock: wall and monotonic advance together unless a
     * test steps the wall clock alone. */
    static final class TestClock implements Clock {
        long wall = 1784700000000L;
        long mono = 1000000L;
        @Override public long wallMs() { return wall; }
        @Override public long monotonicMs() { return mono; }
        void advance(long ms) { wall += ms; mono += ms; }
    }

    /** Deterministic "random" octets: a counter, so EventIds and nonces are
     * distinct and reproducible. */
    static final class CountingRandom implements Auth.RandomSource {
        long counter = 0x30313233L;
        @Override public void nextBytes(byte[] out) {
            long v = counter++;
            for (int i = 0; i < out.length; i++) out[i] = (byte) (v >> ((i % 8) * 8));
        }
    }

    static final Executor INLINE = new Executor() {
        @Override public void execute(Runnable r) { r.run(); }
    };

    static final class Events implements Companion.Listener {
        final List<String> log = new ArrayList<String>();
        @Override public void surfaceChanged(String surface) { log.add("surface:" + surface); }
        @Override public void sessionChanged(SessionState state) { log.add("session:" + state); }
        @Override public void queueChanged() { log.add("queue"); }
        @Override public void settingsRequested() { log.add("settings"); }
        @Override public void diagnostic(String message) { log.add("diag:" + message); }
    }

    static CompanionConfig config(Auth.RandomSource random) {
        Map<String, byte[]> pairings = new HashMap<String, byte[]>();
        pairings.put(PAIRING_ID, TOKEN);
        return new CompanionConfig("test-companion", "0.1.0", pairings, new HashSet<String>(),
            M1Profile.surfaceProfiles(), M1Profile.limits(), random);
    }

    static Companion companion(CompanionStore store, TestClock clock, Events events) {
        return new Companion(config(new CountingRandom()), store, clock, INLINE, events);
    }

    static Json.Obj button(String id, String label, boolean desired) {
        Json.Obj descriptor = new Json.Obj().put("action", "milestone.flag-set")
            .put("args", new Json.Obj().put("desired", desired)).put("when_offline", "queue").put("ttl_s", 300);
        return new Json.Obj().put("t", "button").put("id", id).put("label", label).put("on_tap", descriptor);
    }

    static Json.Obj canvas(String fill) {
        Json.Arr ops = new Json.Arr().add(new Json.Obj().put("op", "rect").put("x", 0).put("y", 0)
            .put("width", 240).put("height", 120).put("fill", fill));
        return new Json.Obj().put("t", "canvas").put("id", "indicator").put("width", 240).put("height", 120).put("ops", ops);
    }

    static Json.Obj milestoneSpec(boolean on) {
        Json.Arr children = new Json.Arr()
            .add(new Json.Obj().put("t", "text").put("text", "EBP milestone").put("style", "title"))
            .add(new Json.Obj().put("t", "text").put("text", on ? "Flag: on" : "Flag: off"))
            .add(canvas(on ? "#2e7d32" : "#c62828"))
            .add(button("set-on", "Set on", true))
            .add(button("set-off", "Set off", false));
        return new Json.Obj().put("t", "column").put("spacing", 8).put("children", children);
    }

    static List<String> noWants() { return new ArrayList<String>(); }
}
