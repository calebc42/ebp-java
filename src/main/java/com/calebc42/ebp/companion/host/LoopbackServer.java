// SPDX-License-Identifier: GPL-3.0-or-later
// The SPEC 5.2 `loopback-tcp` listener: IPv4 loopback only, one reader
// thread per connection feeding the Companion's single ordered executor
// with bounded handoff (the reader waits for each chunk to be consumed, so
// transport backpressure is applied before unbounded memory growth, SPEC
// 22.3), and a sink that writes synchronously to the socket.
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
            try { socket.close(); } catch (IOException ignored) { /* closing */ }
            return;
        }
        final CompanionEngine engine = companion.open(new CompanionEngine.Sink() {
            @Override public void write(byte[] frame) throws IOException {
                synchronized (out) {
                    out.write(frame);
                    out.flush();
                }
            }
        });
        final byte[] buffer = new byte[8192];
        try {
            while (true) {
                final int n = in.read(buffer);
                if (n < 0) break;
                if (n == 0) continue;
                // Bounded handoff: this reader owns exactly one chunk in
                // flight and waits for the ordered executor to consume it.
                final byte[] chunk = new byte[n];
                System.arraycopy(buffer, 0, chunk, 0, n);
                Future<?> done = executor.submit(new Runnable() {
                    @Override public void run() {
                        try {
                            engine.feed(chunk, 0, chunk.length);
                        } catch (Failpoints.Tripped t) {
                            tripHandler.tripped(t);
                        }
                    }
                });
                try {
                    done.get();
                } catch (ExecutionException e) {
                    break;
                }
                if (engine.state() == com.calebc42.ebp.companion.rpc.SessionState.CLOSED) break;
            }
        } catch (IOException e) {
            // transport lost
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.execute(new Runnable() {
                @Override public void run() { engine.endOfStream(); }
            });
            try { socket.close(); } catch (IOException ignored) { /* closing */ }
        }
    }
}
