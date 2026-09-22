// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.wire;

/** SPEC 6.2: conditions that force connection closure. */
public final class FrameClose extends WireException {
    private static final long serialVersionUID = 1L;
    public FrameClose(String message) { super(message); }
}
