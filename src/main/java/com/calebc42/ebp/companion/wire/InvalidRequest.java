// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.wire;

/** SPEC 6.2 / 4.1: non-object top level, batch array, duplicate members (-32600). */
public final class InvalidRequest extends WireException {
    private static final long serialVersionUID = 1L;
    public InvalidRequest(String message) { super(message); }
}
