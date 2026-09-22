// SPDX-License-Identifier: GPL-3.0-or-later
// The JVM host runner: the same Companion core the Android host embeds,
// with a file-backed store, driven by a line control protocol on stdin so
// a real ebp.el peer and the kill matrix can be exercised without a
// device.  Three transports: `--port N` binds loopback TCP (SPEC 5.2),
// `--socket PATH` binds a Unix-domain socket (SPEC 5.3), `--dial PATH`
// dials an Emacs that binds one (SPEC 5.6).  Output lines are one of
// `PORT=<n>`, `SOCKET=<path>`, `DIAL=<path>` once, then `PAIRING=<id>`,
// then one JSON object per line prefixed `EVENT ` (host notifications) or
// `REPLY ` (command replies).  A tripped failpoint halts the process
// immediately, which is the deterministic process death the acceptance
// matrix needs.
package com.calebc42.ebp.companion.host;

import com.calebc42.ebp.companion.auth.Auth;
import com.calebc42.ebp.companion.engine.Bundle;
import com.calebc42.ebp.companion.engine.Clock;
import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.CompanionConfig;
import com.calebc42.ebp.companion.engine.CompanionEngine;
import com.calebc42.ebp.companion.engine.Failpoints;
import com.calebc42.ebp.companion.engine.M1Profile;
import com.calebc42.ebp.companion.engine.Presentation;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.rpc.SessionState;
import com.calebc42.ebp.companion.store.FileStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class HostRunner {

    public static CompanionConfig config(String name, String version, PairingFile pairing, Auth.RandomSource random) {
        Map<String, byte[]> pairings = new HashMap<String, byte[]>();
        pairings.put(pairing.pairingId, pairing.token());
        return new CompanionConfig(name, version, pairings, new HashSet<String>(), M1Profile.surfaceProfiles(), M1Profile.limits(), random);
    }

    private static final PrintStream OUT = new PrintStream(System.out, true);
    private static final String USAGE = "usage: HostRunner --dir DIR [--port N | --socket PATH | --dial PATH]";

    private static synchronized void emit(String prefix, Json.Obj o) {
        OUT.println(prefix + " " + Json.write(o));
    }

    public static void main(String[] args) throws Exception {
        File dir = null;
        int port = 0;
        String socket = null, dial = null;
        int transports = 0;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--dir") && i + 1 < args.length) dir = new File(args[++i]);
            else if (args[i].equals("--port") && i + 1 < args.length) { port = Integer.parseInt(args[++i]); transports++; }
            else if (args[i].equals("--socket") && i + 1 < args.length) { socket = args[++i]; transports++; }
            else if (args[i].equals("--dial") && i + 1 < args.length) { dial = args[++i]; transports++; }
            else { System.err.println(USAGE); System.exit(2); }
        }
        if (dir == null || transports > 1) { System.err.println(USAGE); System.exit(2); }
        final PairingFile pairing = PairingFile.loadOrCreate(new File(dir, "pairing.json"), Auth.SECURE_RANDOM);
        final FileStore store = new FileStore(new File(dir, "store"));
        final ExecutorService executor = LoopbackServer.newOrderedExecutor();
        final Companion.Listener listener = new Companion.Listener() {
            @Override public void surfaceChanged(String surface) {
                emit("EVENT", new Json.Obj().put("event", "surface").put("surface", surface));
            }
            @Override public void sessionChanged(SessionState state) {
                emit("EVENT", new Json.Obj().put("event", "session").put("state", state.name()));
            }
            @Override public void queueChanged() { emit("EVENT", new Json.Obj().put("event", "queue")); }
            @Override public void settingsRequested() { emit("EVENT", new Json.Obj().put("event", "settings")); }
            @Override public void diagnostic(String message) {
                emit("EVENT", new Json.Obj().put("event", "diag").put("message", message));
            }
        };
        final Companion companion = new Companion(config("ebp-java-host", "0.1.0", pairing, Auth.SECURE_RANDOM),
            store, Clock.SYSTEM, executor, listener);
        final LoopbackServer.TripHandler trip = new LoopbackServer.TripHandler() {
            @Override public void tripped(Failpoints.Tripped t) {
                emit("EVENT", new Json.Obj().put("event", "failpoint").put("name", t.name));
                OUT.flush();
                Runtime.getRuntime().halt(3);
            }
        };
        final HostTransport transport;
        if (socket != null) {
            UnixDomainServer server = new UnixDomainServer(companion, executor, trip);
            server.start(Paths.get(socket));
            OUT.println("SOCKET=" + server.path());
            transport = server;
        } else if (dial != null) {
            UnixDomainDialer dialer = new UnixDomainDialer(companion, executor, trip);
            dialer.start(Paths.get(dial));
            OUT.println("DIAL=" + dialer.path());
            transport = dialer;
        } else {
            final LoopbackServer server = new LoopbackServer(companion, executor, trip);
            final int bound = server.start(port);
            OUT.println("PORT=" + bound);
            transport = new HostTransport() {
                @Override public String profile() { return "loopback-tcp"; }
                @Override public String address() { return Integer.toString(bound); }
                @Override public void stop() { server.stop(); }
            };
        }
        OUT.println("PAIRING=" + pairing.pairingId);
        // The token is written to the pairing file only; the peer reads it
        // out of band (the test harness owns the directory).
        final SoftwareRasterizer rasterizer = new SoftwareRasterizer();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            final String[] cmd = line.trim().split("\\s+");
            if (cmd.length == 0 || cmd[0].isEmpty()) continue;
            try {
                if (cmd[0].equals("quit")) break;
                if (cmd[0].equals("status")) {
                    emit("REPLY", status(companion, transport, pairing.pairingId));
                } else if (cmd[0].equals("snapshot") && cmd.length == 2) {
                    Future<Json.Obj> f = executor.submit(new java.util.concurrent.Callable<Json.Obj>() {
                        @Override public Json.Obj call() {
                            Presentation p = companion.presentation(pairing.pairingId, cmd[1]);
                            byte[] bundle = Bundle.build(p, companion.generation(), rasterizer);
                            Json.Obj doc = Bundle.document(bundle);
                            // Report a checksum-style summary of every raster so a
                            // test can assert the indicator color without pixels.
                            Json.Arr colors = new Json.Arr();
                            Json.Arr items = doc.array("items");
                            for (int i = 0; i < items.size(); i++) {
                                Json.Obj it = items.get(i).asObject();
                                if (!"canvas".equals(it.string("kind"))) continue;
                                int off = 4 + Json.utf8Length(Json.write(doc)) + it.integer("raster_offset").intValue();
                                long bytes = it.integer("raster_bytes").longValue();
                                if (bytes >= 4) {
                                    int px = (bundle[off] & 0xFF) | ((bundle[off + 1] & 0xFF) << 8)
                                        | ((bundle[off + 2] & 0xFF) << 16) | ((bundle[off + 3] & 0xFF) << 24);
                                    colors.add(String.format("#%08x", px));
                                } else colors.add("");
                            }
                            doc.put("first_pixels", colors).put("bundle_bytes", bundle.length);
                            return doc;
                        }
                    });
                    emit("REPLY", f.get());
                } else if ((cmd[0].equals("tap") || cmd[0].equals("tap-id")) && cmd.length == 3) {
                    final boolean byId = cmd[0].equals("tap-id");
                    Future<Json.Obj> f = executor.submit(new java.util.concurrent.Callable<Json.Obj>() {
                        @Override public Json.Obj call() {
                            Presentation p = companion.presentation(pairing.pairingId, cmd[1]);
                            String path = cmd[2];
                            if (byId) {
                                path = null;
                                for (Presentation.Item it : p.items)
                                    if (cmd[2].equals(it.node.string("id"))) path = it.path;
                                if (path == null) return new Json.Obj().put("admission", "NOT_PRESENTED");
                            }
                            try {
                                Companion.Admission a = companion.activate(pairing.pairingId, cmd[1], p.revision, path);
                                return new Json.Obj().put("admission", a.name()).put("path", path).put("revision", p.revision);
                            } catch (Failpoints.Tripped t) {
                                trip.tripped(t);
                                return new Json.Obj();
                            }
                        }
                    });
                    emit("REPLY", f.get());
                } else if (cmd[0].equals("failpoint") && cmd.length == 2) {
                    emit("REPLY", new Json.Obj().put("armed", Failpoints.arm(cmd[1])));
                } else if (cmd[0].equals("close-transport")) {
                    final CompanionEngine e = companion.currentEngine();
                    Future<?> f = executor.submit(new Runnable() {
                        @Override public void run() { if (e != null) e.close("host closed transport"); }
                    });
                    f.get();
                    emit("REPLY", new Json.Obj().put("closed", e != null));
                } else if (cmd[0].equals("halt")) {
                    OUT.flush();
                    Runtime.getRuntime().halt(9);
                } else {
                    emit("REPLY", new Json.Obj().put("error", "unknown command"));
                }
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                if (c instanceof Failpoints.Tripped) trip.tripped((Failpoints.Tripped) c);
                emit("REPLY", new Json.Obj().put("error", String.valueOf(c)));
            }
        }
        transport.stop();
        executor.shutdown();
        store.close();
        System.exit(0);
    }

    static Json.Obj status(Companion companion, HostTransport transport, String pairingId) {
        CompanionEngine e = companion.currentEngine();
        Json.Obj o = new Json.Obj().put("running", true)
            .put("transport", new Json.Obj().put("profile", transport.profile()).put("address", transport.address()))
            .put("session", e == null ? "none" : e.state().name()).put("pairing_id", pairingId)
            .put("queued_events", companion.queuedCount(pairingId)).put("in_flight", companion.inFlight(pairingId))
            .put("pump_paused", e != null && e.pumpPaused()).put("generation", companion.generation())
            .put("surfaces", companion.welcomeSurfaces(pairingId));
        if ("loopback-tcp".equals(transport.profile())) o.put("port", Integer.parseInt(transport.address()));
        String err = companion.lastError();
        if (err != null) o.put("last_error", err);
        return o;
    }
}
