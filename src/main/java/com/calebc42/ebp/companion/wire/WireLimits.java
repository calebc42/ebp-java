// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.wire;

/** SPEC 4.5 fixed limits (contract.json `limits.fixed`). */
public final class WireLimits {
    private WireLimits() {}

    public static final int MAX_HEADER_OCTETS = 8192;
    public static final int MAX_BODY_OCTETS = 4194304;
    public static final int MAX_JSON_DEPTH = 64;
    public static final int MAX_NODES_PER_SNAPSHOT = 10000;
    public static final int MAX_CHILDREN_PER_NODE = 10000;
    public static final int MAX_VARIANTS_PER_HOST = 8;
    public static final int MAX_SEMANTIC_ACTIONS_PER_NODE = 8;
    public static final int MAX_IDENTIFIER_OCTETS = 128;
    public static final int MAX_REQUEST_ID_OCTETS = 64;
    public static final int MAX_METHOD_OCTETS = 128;
    /** SPEC 4.5: the header section a SENDER may rely on a peer accepting. */
    public static final int MAX_SEND_HEADER_OCTETS = 128;
    /** SPEC 4.5 (amendment #108): node nesting depth in one document. */
    public static final int MAX_NODE_DEPTH = 20;
}
