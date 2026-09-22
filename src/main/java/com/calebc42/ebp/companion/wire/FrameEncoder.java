// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.wire;

import java.nio.charset.StandardCharsets;

/** SPEC 6.1 sender: exact header syntax, octet-counted UTF-8 body. */
public final class FrameEncoder {
    private FrameEncoder() {}

    public static byte[] encode(String jsonText) {
        byte[] body = jsonText.getBytes(StandardCharsets.UTF_8);
        if (body.length > WireLimits.MAX_BODY_OCTETS)
            throw new FrameClose("body exceeds max_frame_bytes");
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        // SPEC 4.5/6.1: a sender MUST NOT rely on a peer accepting a header
        // section longer than the 128-octet figure.
        if (header.length > WireLimits.MAX_SEND_HEADER_OCTETS)
            throw new IllegalStateException("header section exceeds max_send_header_bytes");
        byte[] frame = new byte[header.length + body.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(body, 0, frame, header.length, body.length);
        return frame;
    }
}
