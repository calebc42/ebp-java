// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.wire;

/** SPEC 6.2: EOF in the middle of a frame terminates the session. */
public final class FrameIncomplete extends WireException {
    private static final long serialVersionUID = 1L;
    public FrameIncomplete(String message) { super(message); }
}
