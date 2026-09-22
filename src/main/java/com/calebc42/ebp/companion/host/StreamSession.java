// SPDX-License-Identifier: GPL-3.0-or-later
// One accepted (or dialed) stream, served to the Companion: a reader that
// feeds the single ordered executor with bounded handoff (the reader owns
// exactly one chunk in flight and waits for it to be consumed, so transport
// backpressure is applied before unbounded memory growth, SPEC 22.3), and a
// sink that writes synchronously to the stream.  Transport-neutral: TCP,
// Unix-domain, and Android LocalSocket connections all reduce to an
// InputStream, an OutputStream, and something to close.
package com.calebc42.ebp.companion.host;

import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.CompanionEngine;
import com.calebc42.ebp.companion.engine.Failpoints;
import com.calebc42.ebp.companion.rpc.SessionState;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class StreamSession {

    private StreamSession() {}

    /** Serve one connection until it ends; returns when the stream is done.
     * POOL may be null for a dialed connection, which SPEC 5.7 does not
     * pool: the pre-authentication bound is the binder's. */
    public static void serve(Companion companion, final ExecutorService executor,
                             final LoopbackServer.TripHandler tripHandler, PreauthPool pool,
                             InputStream in, final OutputStream out, Closeable socket) {
        final CompanionEngine engine = companion.open(new CompanionEngine.Sink() {
            @Override public void write(byte[] frame) throws IOException {
                synchronized (out) {
                    out.write(frame);
                    out.flush();
                }
            }
        });
        final byte[] buffer = new byte[8192];
        boolean pooled = pool != null;
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
                SessionState state = engine.state();
                if (pooled && state != SessionState.CONNECTED && state != SessionState.CHALLENGED) {
                    // SPEC 5.7: authenticated (or already closed) — no
                    // longer a pre-authentication connection.
                    pool.remove(socket);
                    pooled = false;
                }
                if (state == SessionState.CLOSED) break;
            }
        } catch (IOException e) {
            // transport lost
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (pooled) pool.remove(socket);
            executor.execute(new Runnable() {
                @Override public void run() { engine.endOfStream(); }
            });
            try { socket.close(); } catch (IOException ignored) { /* closing */ }
        }
    }
}
