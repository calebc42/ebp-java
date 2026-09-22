// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.engine;

/** SPEC 13.2/16.1: structurally valid params contain an invalid surface,
 * node, or action object — `1201 content-invalid` with `data.path`. */
public final class ContentInvalid extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final String path;
    public final String reason;
    public ContentInvalid(String path, String reason) {
        super(reason + " at " + path);
        this.path = path;
        this.reason = reason;
    }
}
