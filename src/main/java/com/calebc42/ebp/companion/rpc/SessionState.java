// SPDX-License-Identifier: GPL-3.0-or-later
// EBP 3 session lifecycle, Companion view.  Implements ebp/SPEC.md 10.1.
package com.calebc42.ebp.companion.rpc;

/** SPEC 10.1 connection states as the Companion (the server) sees them. */
public enum SessionState {
    CONNECTED, CHALLENGED, SYNCING, READY, CLOSED;

    /** Companion-side transition triggers. */
    public enum Event {
        /** `session.hello` validated and the nonce response issued. */
        HELLO_ACCEPTED,
        /** `auth.response` proof verified and the welcome committed. */
        AUTH_VERIFIED,
        /** `session.ready` succeeded; its response is serialized first (10.3). */
        READY_CONFIRMED,
        /** Any failure, timeout, replacement, or shutdown (10.1). */
        CLOSE
    }

    /** Pure transition function; null means the event is illegal in STATE. */
    public static SessionState step(SessionState state, Event event) {
        if (event == Event.CLOSE) return CLOSED;
        if (state == CONNECTED && event == Event.HELLO_ACCEPTED) return CHALLENGED;
        if (state == CHALLENGED && event == Event.AUTH_VERIFIED) return SYNCING;
        if (state == SYNCING && event == Event.READY_CONFIRMED) return READY;
        return null;
    }
}
