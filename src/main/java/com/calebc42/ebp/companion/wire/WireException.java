// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.wire;

/** Base of the SPEC 6.2 receiver error taxonomy. */
public abstract class WireException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    protected WireException(String message) { super(message); }
}
