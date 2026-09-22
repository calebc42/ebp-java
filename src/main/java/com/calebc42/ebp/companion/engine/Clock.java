// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.engine;

/** Injectable time source: the raw wall clock (SPEC 15.2 never trusts it
 * alone) and a monotonic elapsed-time reading that corroborates it. */
public interface Clock {
    long wallMs();
    long monotonicMs();

    Clock SYSTEM = new Clock() {
        @Override public long wallMs() { return System.currentTimeMillis(); }
        @Override public long monotonicMs() { return System.nanoTime() / 1000000L; }
    };
}
