// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.store;

/** A durable-store failure.  SPEC 15.1/13.2: the caller MUST NOT claim the
 * mutation happened; the store guarantees the partition was rolled back. */
public class StoreException extends Exception {
    private static final long serialVersionUID = 1L;
    public StoreException(String message) { super(message); }
    public StoreException(String message, Throwable cause) { super(message, cause); }
}
