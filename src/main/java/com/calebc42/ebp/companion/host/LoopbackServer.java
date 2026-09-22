// SPDX-License-Identifier: GPL-3.0-or-later
// The SPEC 5.2 `loopback-tcp` listener: IPv4 loopback only.  Each accepted
// socket enters the SPEC 5.7 pre-authentication pool and is served by
// StreamSession on its own reader thread.
package com.calebc42.ebp.companion.host;

import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.CompanionEngine;
import com.calebc42.ebp.companion.engine.Failpoints;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class LoopbackServer {

    /** Called with a failpoint trip from any thread: the host decides how
     * to die (the runner halts the process). */
    public interface TripHandler {
        void tripped(Failpoints.Tripped t);
    }

    private final Companion companion;
    private final ExecutorService executor;
    private final TripHandler tripHandler;
    private final PreauthPool pool = new PreauthPool();
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;

    /** Create the single ordered executor a Companion must be built on. */
    public static ExecutorService newOrderedExecutor() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ebp-companion");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public LoopbackServer(Companion companion, ExecutorService executor, TripHandler tripHandler) {
        this.companion = companion;
        this.executor = executor;
        this.tripHandler = tripHandler;
    }

    /** Bind 127.0.0.1:PORT (0 = ephemeral) and start accepting. */
    public synchronized int start(int port) throws IOException {
        if (running) return server.getLocalPort();
        server = new ServerSocket();
        server.setReuseAddress(true);
        // SPEC 5.2: bind only the IPv4 loopback interface.
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "ebp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return server.getLocalPort();
    }

    public synchronized int port() { return server == null ? -1 : server.getLocalPort(); }

    public synchronized void stop() {
        running = false;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) { /* closing */ }
        }
        pool.shutdown();
        final CompanionEngine current = companion.currentEngine();
        if (current != null) {
            executor.execute(new Runnable() {
                @Override public void run() { current.close("host shutdown"); }
            });
        }
    }

    private void acceptLoop() {
        while (running) {
            final Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (running) continue;
                return;
            }
            try {
                socket.setTcpNoDelay(true);
            } catch (IOException ignored) {
                // best effort
            }
            // SPEC 5.7: pooled from accept, newest wins.
            pool.admit(socket);
            Thread reader = new Thread(new Runnable() {
                @Override public void run() { serve(socket); }
            }, "ebp-reader");
            reader.setDaemon(true);
            reader.start();
        }
    }

    private void serve(final Socket socket) {
        final OutputStream out;
        final InputStream in;
        try {
            out = socket.getOutputStream();
            in = socket.getInputStream();
        } catch (IOException e) {
            pool.remove(socket);
            try { socket.close(); } catch (IOException ignored) { /* closing */ }
            return;
        }
        StreamSession.serve(companion, executor, tripHandler, pool, in, out, socket);
    }
}
