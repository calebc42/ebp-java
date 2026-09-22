// SPDX-License-Identifier: GPL-3.0-or-later
// The SPEC 5.3 `loopback-unix` listener for a desktop JVM: a filesystem
// Unix-domain socket in a directory private to this user (created 0700;
// refused when owned by someone else or accessible by group or others),
// the socket file itself 0600, a stale socket of ours unlinked before the
// bind, a connectable one refused (another Companion authority on the
// path).  Every accepted channel enters the SPEC 5.7 pool and is served by
// StreamSession.  JDK 16+ (UnixDomainSocketAddress); this tier is never
// vendored into the Emacs tree.
package com.calebc42.ebp.companion.host;

import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.CompanionEngine;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public final class UnixDomainServer implements HostTransport {

    private final Companion companion;
    private final ExecutorService executor;
    private final LoopbackServer.TripHandler tripHandler;
    private final PreauthPool pool = new PreauthPool();
    private ServerSocketChannel server;
    private Path path;
    private volatile boolean running;

    public UnixDomainServer(Companion companion, ExecutorService executor,
                            LoopbackServer.TripHandler tripHandler) {
        this.companion = companion;
        this.executor = executor;
        this.tripHandler = tripHandler;
    }

    /** SPEC 5.3: the directory must be ours and closed to everyone else. */
    static void ensurePrivateDirectory(Path dir) throws IOException {
        Set<PosixFilePermission> owner = PosixFilePermissions.fromString("rwx------");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(owner));
            Files.setPosixFilePermissions(dir, owner);
        }
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir))
            throw new IOException("socket directory is not a plain directory: " + dir);
        if (!Files.getOwner(dir).equals(currentUser(dir)))
            throw new IOException("socket directory is not owned by this user: " + dir);
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir);
        for (PosixFilePermission p : perms)
            if (!owner.contains(p))
                throw new IOException("socket directory is accessible by others: " + dir);
    }

    static UserPrincipal currentUser(Path any) throws IOException {
        return any.getFileSystem().getUserPrincipalLookupService()
            .lookupPrincipalByName(System.getProperty("user.name"));
    }

    /** Non-null when something accepts connections at PATH right now. */
    static boolean connectable(Path path) {
        try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(path));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Bind PATH and start accepting. */
    public synchronized void start(Path socketPath) throws IOException {
        if (running) return;
        Path abs = socketPath.toAbsolutePath();
        ensurePrivateDirectory(abs.getParent());
        if (Files.exists(abs)) {
            if (!Files.getOwner(abs).equals(currentUser(abs)))
                throw new IOException("socket file is not owned by this user: " + abs);
            if (connectable(abs))
                throw new IOException("socket in use: " + abs);
            Files.delete(abs);
        }
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(abs), 8);
        Files.setPosixFilePermissions(abs, EnumSet.of(PosixFilePermission.OWNER_READ,
                                                      PosixFilePermission.OWNER_WRITE));
        path = abs;
        running = true;
        Thread t = new Thread(this::acceptLoop, "ebp-accept-unix");
        t.setDaemon(true);
        t.start();
    }

    public synchronized Path path() { return path; }

    @Override public String profile() { return "loopback-unix"; }
    @Override public String address() { Path p = path(); return p == null ? "" : p.toString(); }

    @Override public synchronized void stop() {
        running = false;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) { /* closing */ }
        }
        pool.shutdown();
        if (path != null) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { /* best effort */ }
        }
        final CompanionEngine current = companion.currentEngine();
        if (current != null) executor.execute(() -> current.close("host shutdown"));
    }

    private void acceptLoop() {
        while (running) {
            final SocketChannel ch;
            try {
                ch = server.accept();
            } catch (IOException e) {
                if (running) continue;
                return;
            }
            pool.admit(ch);
            Thread reader = new Thread(() -> StreamSession.serve(
                companion, executor, tripHandler, pool,
                UnixChannelStreams.input(ch), UnixChannelStreams.output(ch), ch), "ebp-reader-unix");
            reader.setDaemon(true);
            reader.start();
        }
    }
}
