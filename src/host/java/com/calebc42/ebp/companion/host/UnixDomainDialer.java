// SPDX-License-Identifier: GPL-3.0-or-later
// The SPEC 5.6 `emacs-unix` dialer for a desktop JVM: Emacs binds a
// filesystem Unix-domain socket, this Companion dials it.  A socket file
// owned by another user is refused outright (terminal).  A missing
// socket, a refused connect, or a close the Companion did not initiate is
// followed by the SPEC 5.7 jittered bounded redial: 1 s doubling to 15 s,
// scaled by a factor in [0.75, 1.0].  Each connection is served by
// StreamSession without a pool: the pre-authentication bound belongs to
// the binder.  JDK 16+; never vendored into the Emacs tree.
package com.calebc42.ebp.companion.host;

import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.CompanionEngine;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;

public final class UnixDomainDialer implements HostTransport {

    public static final long INITIAL_DELAY_MS = 1000L;
    public static final long MAX_DELAY_MS = 15000L;

    private final Companion companion;
    private final ExecutorService executor;
    private final LoopbackServer.TripHandler tripHandler;
    private Path path;
    private Thread thread;
    private volatile boolean running;
    private volatile SocketChannel current;
    private volatile String lastError;
    private volatile int attempt;

    public UnixDomainDialer(Companion companion, ExecutorService executor,
                            LoopbackServer.TripHandler tripHandler) {
        this.companion = companion;
        this.executor = executor;
        this.tripHandler = tripHandler;
    }

    /** Start dialing PATH; returns at once, the dial loop is a daemon thread. */
    public synchronized void start(Path socketPath) {
        if (running) return;
        path = socketPath.toAbsolutePath();
        running = true;
        thread = new Thread(this::dialLoop, "ebp-dialer-unix");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized Path path() { return path; }
    public String lastError() { return lastError; }

    @Override public String profile() { return "emacs-unix"; }
    @Override public String address() { Path p = path(); return p == null ? "" : p.toString(); }

    @Override public void stop() {
        running = false;
        SocketChannel ch = current;
        if (ch != null) {
            try { ch.close(); } catch (IOException ignored) { /* closing */ }
        }
        Thread t;
        synchronized (this) { t = thread; }
        if (t != null) t.interrupt();
        final CompanionEngine engine = companion.currentEngine();
        if (engine != null) executor.execute(() -> engine.close("host shutdown"));
    }

    /** SPEC 5.7 jittered bounded backoff for ATTEMPT (0-based). */
    static long delayMs(int attempt) {
        long base = Math.min(MAX_DELAY_MS, INITIAL_DELAY_MS << Math.min(attempt, 20));
        double jitter = 0.75 + 0.25 * ThreadLocalRandom.current().nextDouble();
        return (long) (base * jitter);
    }

    private void dialLoop() {
        while (running) {
            try {
                if (Files.exists(path)
                    && !Files.getOwner(path).equals(UnixDomainServer.currentUser(path))) {
                    // SPEC 5.6: terminal, never redialed.
                    lastError = "socket owner mismatch: " + path;
                    running = false;
                    return;
                }
                SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                ch.connect(UnixDomainSocketAddress.of(path));
                current = ch;
                attempt = 0;
                StreamSession.serve(companion, executor, tripHandler, null,
                                    UnixChannelStreams.input(ch), UnixChannelStreams.output(ch), ch);
                current = null;
            } catch (IOException e) {
                lastError = String.valueOf(e);
            }
            if (!running) return;
            try {
                Thread.sleep(delayMs(attempt++));
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
