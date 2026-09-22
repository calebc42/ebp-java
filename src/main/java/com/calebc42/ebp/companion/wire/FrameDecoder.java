// SPDX-License-Identifier: GPL-3.0-or-later
// EBP 3 framing, Companion side.  Implements ebp/SPEC.md sections 6 and
// 4.1 — the Java twin of ebp-kmp's FrameCodec and of the canonical ebp.el
// decoder: same error taxonomy, same chunk-size independence, verified by
// the same goldens/wire corpus.
package com.calebc42.ebp.companion.wire;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.json.JsonParser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental SPEC 6.2 receiver.  Feed transport reads of any size; complete
 * messages come back in wire order.  Errors are thrown as the taxonomy in
 * this package.  Parse state lives on the decoder (the header is parsed
 * once; the terminator scan resumes at a watermark; reads append in place).
 */
public final class FrameDecoder {

    /** Receives each complete message in wire order. */
    public interface Consumer {
        void accept(Json.Obj message);
    }

    private byte[] buffer = new byte[8192];
    private int size = 0;       // valid bytes in buffer
    private int offset = 0;     // consumed bytes — the current frame starts here
    private int scanned = 0;    // no terminator STARTS before this index
    private int expected = -1;  // declared body length, once the header parsed
    private int bodyStart = -1; // absolute index of the body's first byte

    private static final byte CR = '\r';
    private static final byte LF = '\n';

    public List<Json.Obj> feed(byte[] bytes) {
        final List<Json.Obj> out = new ArrayList<Json.Obj>();
        feed(bytes, 0, bytes.length, new Consumer() {
            @Override public void accept(Json.Obj message) { out.add(message); }
        });
        return out;
    }

    public void feed(byte[] bytes, Consumer consumer) {
        feed(bytes, 0, bytes.length, consumer);
    }

    /**
     * Decode LENGTH bytes starting at SOURCE-OFFSET.  Good frames are handed
     * to CONSUMER before a bad frame throws, so a well-formed frame pipelined
     * ahead of a bad one in the same read is not lost; after a recoverable
     * body fault the decoder has already advanced past the bad frame, so a
     * further feed() with no new bytes drains what follows.
     */
    public void feed(byte[] bytes, int sourceOffset, int length, Consumer consumer) {
        if (sourceOffset < 0 || length < 0 || sourceOffset > bytes.length
            || length > bytes.length - sourceOffset)
            throw new IllegalArgumentException("invalid input slice");
        append(bytes, sourceOffset, length);
        while (true) {
            if (expected < 0) {
                int term = indexOfTerminator(Math.max(offset, scanned));
                if (term < 0) {
                    scanned = Math.max(offset, size - 3);
                    // SPEC 6.2: the header section may not exceed 8,192 octets.
                    if (size - offset > WireLimits.MAX_HEADER_OCTETS)
                        throw new FrameClose("header section too large");
                    return;
                }
                if (term - offset + 4 > WireLimits.MAX_HEADER_OCTETS)
                    throw new FrameClose("header section too large");
                expected = parseHeader(new String(buffer, offset, term - offset,
                    StandardCharsets.ISO_8859_1));
                bodyStart = term + 4;
            }
            if (size - bodyStart < expected) return; // retain partial data
            String text = decodeBody(bodyStart, expected);
            offset = bodyStart + expected;
            scanned = offset;
            expected = -1;
            bodyStart = -1;
            compact();
            // The complete body is consumed before malformed UTF-8 is reported
            // so the recoverable-error drain resumes at the following frame.
            if (text == null) throw new WireParseError("invalid UTF-8");
            consumer.accept(parseBody(text));
        }
    }

    /** SPEC 6.2: EOF mid-frame terminates the session. */
    public void finish() {
        if (size - offset > 0)
            throw new FrameIncomplete("stream ended with " + (size - offset) + " pending octets");
    }

    /** Octets buffered for an incomplete frame (diagnostics only). */
    public int pendingOctets() { return size - offset; }

    private void append(byte[] bytes, int sourceOffset, int length) {
        if (size + length > buffer.length) {
            byte[] grown = new byte[Math.max(buffer.length * 2, size + length)];
            System.arraycopy(buffer, 0, grown, 0, size);
            buffer = grown;
        }
        System.arraycopy(bytes, sourceOffset, buffer, size, length);
        size += length;
    }

    /** Reclaim consumed space between frames (never while a header/body is
     * mid-parse — `bodyStart` is absolute). */
    private void compact() {
        if (offset == size) {
            offset = 0; size = 0; scanned = 0;
        } else if (offset > 65536) {
            System.arraycopy(buffer, offset, buffer, 0, size - offset);
            size -= offset;
            scanned -= offset;
            offset = 0;
        }
    }

    private int indexOfTerminator(int from) {
        byte[] buf = buffer;
        for (int i = from; i <= size - 4; i++) {
            if (buf[i] == CR && buf[i + 1] == LF && buf[i + 2] == CR && buf[i + 3] == LF)
                return i;
        }
        return -1;
    }

    /** SPEC 6.2 header rules; returns the declared body length. */
    private static int parseHeader(String head) {
        List<Long> lengths = new ArrayList<Long>();
        int pos = 0;
        while (true) {
            int nl = head.indexOf("\r\n", pos);
            String line = nl < 0 ? head.substring(pos) : head.substring(pos, nl);
            if (line.isEmpty()) throw new FrameClose("malformed header line");
            int colon = line.indexOf(':');
            if (colon < 0) throw new FrameClose("malformed header line");
            String name = line.substring(0, colon);
            for (int k = 0; k < name.length(); k++) {
                char c = name.charAt(k);
                if (c > 0x7E || c < 0x21) throw new FrameClose("malformed header name");
            }
            // Receiver MAY accept optional horizontal whitespace around a value;
            // every other value octet must be printable ASCII or HTAB.
            String rawValue = line.substring(colon + 1);
            for (int k = 0; k < rawValue.length(); k++) {
                char c = rawValue.charAt(k);
                if (!(c == '\t' || (c >= 0x20 && c <= 0x7E)))
                    throw new FrameClose("malformed header value");
            }
            String value = trimOws(rawValue);
            if (name.equalsIgnoreCase("Content-Length")) {
                if (!isDecimal(value)) throw new FrameClose("invalid Content-Length value");
                long parsed;
                try {
                    parsed = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new FrameClose("overflowing Content-Length");
                }
                lengths.add(Long.valueOf(parsed));
            }
            if (nl < 0) break;
            pos = nl + 2;
        }
        if (lengths.isEmpty()) throw new FrameClose("missing Content-Length");
        if (lengths.size() > 1) throw new FrameClose("duplicate Content-Length");
        long length = lengths.get(0).longValue();
        // SPEC 6.2: close immediately on an oversized declaration.
        if (length > WireLimits.MAX_BODY_OCTETS) throw new FrameClose("oversized body declaration");
        return (int) length;
    }

    private static String trimOws(String v) {
        int a = 0, b = v.length();
        while (a < b && (v.charAt(a) == ' ' || v.charAt(a) == '\t')) a++;
        while (b > a && (v.charAt(b - 1) == ' ' || v.charAt(b - 1) == '\t')) b--;
        return v.substring(a, b);
    }

    /** `0|[1-9][0-9]*` — unsigned decimal, no sign, no leading zeroes. */
    private static boolean isDecimal(String v) {
        if (v.isEmpty()) return false;
        if (v.equals("0")) return true;
        if (v.charAt(0) < '1' || v.charAt(0) > '9') return false;
        for (int k = 1; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** SPEC 6.2 + 4.1: strict UTF-8 (malformed input is reported, never
     * substituted), returning null on failure. */
    private String decodeBody(int start, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer, start, length)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static Json.Obj parseBody(String text) {
        Json.Value value = JsonParser.parse(text);
        if (!(value instanceof Json.Obj))
            throw new InvalidRequest("top-level value is not a single message object");
        return (Json.Obj) value;
    }
}
