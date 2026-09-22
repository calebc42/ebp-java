// SPDX-License-Identifier: GPL-3.0-or-later
// Stream views of a blocking SocketChannel that a reader thread and a
// writer thread can use at the same time.  `Channels.newInputStream` and
// `newOutputStream` cannot: for a SelectableChannel both synchronize on
// the channel's blocking lock for the whole call, so a pending read holds
// every write until the peer sends something — a sink write from the
// Companion's executor then stalls behind the reader (deadlock for as
// long as Emacs is silent).  SocketChannel itself takes separate read and
// write locks, so calling it directly is safe.
package com.calebc42.ebp.companion.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

final class UnixChannelStreams {

    private UnixChannelStreams() {}

    static InputStream input(final SocketChannel ch) {
        return new InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xFF;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                return ch.read(ByteBuffer.wrap(b, off, len));
            }
            @Override public void close() throws IOException { ch.close(); }
        };
    }

    static OutputStream output(final SocketChannel ch) {
        return new OutputStream() {
            @Override public void write(int b) throws IOException {
                write(new byte[] { (byte) b }, 0, 1);
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                ByteBuffer bb = ByteBuffer.wrap(b, off, len);
                while (bb.hasRemaining()) ch.write(bb);
            }
            @Override public void close() throws IOException { ch.close(); }
        };
    }
}
