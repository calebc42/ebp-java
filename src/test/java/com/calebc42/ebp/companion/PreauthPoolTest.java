// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 5.7: the pre-authentication pool — bound, deadline, newest wins.
package com.calebc42.ebp.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.calebc42.ebp.companion.host.PreauthPool;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class PreauthPoolTest {

    private static final class Sock implements Closeable {
        volatile boolean closed;
        final CountDownLatch latch = new CountDownLatch(1);
        @Override public void close() throws IOException { closed = true; latch.countDown(); }
    }

    @Test public void newestWinsAtTheBound() {
        PreauthPool pool = new PreauthPool(2, 60000L);
        Sock a = new Sock(), b = new Sock(), c = new Sock();
        pool.admit(a);
        pool.admit(b);
        assertEquals(2, pool.size());
        pool.admit(c);
        assertEquals("the third dial evicts the oldest", 2, pool.size());
        assertTrue(a.closed);
        assertFalse(b.closed);
        assertFalse(c.closed);
        assertTrue(pool.contains(b));
        assertTrue(pool.contains(c));
        pool.shutdown();
    }

    @Test public void deadlineClosesAnUnauthenticatedConnection() throws Exception {
        PreauthPool pool = new PreauthPool(8, 150L);
        Sock a = new Sock();
        pool.admit(a);
        assertTrue("closed at the deadline", a.latch.await(3, TimeUnit.SECONDS));
        assertTrue(a.closed);
        assertEquals(0, pool.size());
        pool.shutdown();
    }

    @Test public void authenticatedConnectionLeavesThePoolOpen() throws Exception {
        PreauthPool pool = new PreauthPool(8, 150L);
        Sock a = new Sock();
        pool.admit(a);
        pool.remove(a);
        assertFalse(pool.contains(a));
        Thread.sleep(400L);
        assertFalse("removal cancels the deadline", a.closed);
        pool.shutdown();
        assertFalse("shutdown closes only pooled connections", a.closed);
    }

    @Test public void shutdownClosesEverythingPending() {
        PreauthPool pool = new PreauthPool(8, 60000L);
        Sock a = new Sock(), b = new Sock();
        pool.admit(a);
        pool.admit(b);
        pool.shutdown();
        assertTrue(a.closed);
        assertTrue(b.closed);
        assertEquals(0, pool.size());
        Sock late = new Sock();
        pool.admit(late);
        assertTrue("a pool that is shut refuses by closing", late.closed);
    }
}
