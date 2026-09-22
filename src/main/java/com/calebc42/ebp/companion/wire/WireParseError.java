// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.wire;

/** SPEC 6.2: invalid UTF-8 or invalid JSON after a complete body (-32700). */
public final class WireParseError extends WireException {
    private static final long serialVersionUID = 1L;
    public WireParseError(String message) { super(message); }
}
