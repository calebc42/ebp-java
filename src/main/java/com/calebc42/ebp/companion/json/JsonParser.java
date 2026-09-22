// SPDX-License-Identifier: GPL-3.0-or-later
// The strict RFC 8259 parser at the frame boundary (SPEC 4.1/4.2/4.5),
// ported from ebp-kmp's EbpJson.  Everything is enforced in one pass:
// grammar, duplicate member names, nesting depth <= 64, surrogate pairing,
// integer magnitude <= 2^53-1, finite numbers, no trailing data.
//
// Error taxonomy is the wire's: a malformed text is WireParseError
// (-32700); duplicate member names are InvalidRequest (-32600, SPEC 4.1).
package com.calebc42.ebp.companion.json;

import com.calebc42.ebp.companion.wire.InvalidRequest;
import com.calebc42.ebp.companion.wire.WireLimits;
import com.calebc42.ebp.companion.wire.WireParseError;

public final class JsonParser {
    private final String s;
    private final int maxDepth;
    private int i;

    private JsonParser(String s, int maxDepth) { this.s = s; this.maxDepth = maxDepth; }

    /** Parse one complete JSON text strictly under the wire's SPEC 4.5
     * nesting limit. */
    public static Json.Value parse(String text) {
        return parse(text, WireLimits.MAX_JSON_DEPTH);
    }

    /** Parse with an explicit nesting allowance.  Only persisted store text
     * uses a larger value than the wire limit: a stored record wraps event
     * args that may legally sit near the wire's own cap. */
    public static Json.Value parse(String text, int maxDepth) {
        JsonParser p = new JsonParser(text, maxDepth);
        p.skipWs();
        Json.Value v = p.value(0);
        p.skipWs();
        if (!p.atEnd()) throw new WireParseError("trailing data after message");
        return v;
    }

    private boolean atEnd() { return i >= s.length(); }

    private void skipWs() {
        // RFC 8259 ws is exactly these four characters.
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private Json.Value value(int depth) {
        if (atEnd()) throw new WireParseError("unexpected end of message");
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj(depth + 1);
            case '[': return arr(depth + 1);
            case '"': return new Json.Str(string());
            case 't': literal("true"); return Json.Bool.TRUE;
            case 'f': literal("false"); return Json.Bool.FALSE;
            case 'n': literal("null"); return Json.Null.NULL;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw new WireParseError("invalid JSON");
        }
    }

    private void checkDepth(int depth) {
        // SPEC 4.5: at most 64 nested containers; the 65th open rejects.
        if (depth > maxDepth)
            throw new WireParseError("nesting depth exceeds " + maxDepth);
    }

    private Json.Obj obj(int depth) {
        checkDepth(depth);
        i++; // '{'
        Json.Obj members = new Json.Obj();
        skipWs();
        if (!atEnd() && s.charAt(i) == '}') { i++; return members; }
        while (true) {
            skipWs();
            if (atEnd() || s.charAt(i) != '"')
                throw new WireParseError("object member name must be a string");
            String key = string();
            // SPEC 4.1: duplicate member names invalidate the message.
            if (members.has(key)) throw new InvalidRequest("duplicate member names");
            skipWs();
            if (atEnd() || s.charAt(i) != ':') throw new WireParseError("expected ':'");
            i++;
            skipWs();
            members.put(key, value(depth));
            skipWs();
            if (atEnd()) throw new WireParseError("unexpected end of message");
            char c = s.charAt(i);
            if (c == ',') { i++; }
            else if (c == '}') { i++; return members; }
            else throw new WireParseError("expected ',' or '}'");
        }
    }

    private Json.Arr arr(int depth) {
        checkDepth(depth);
        i++; // '['
        Json.Arr items = new Json.Arr();
        skipWs();
        if (!atEnd() && s.charAt(i) == ']') { i++; return items; }
        while (true) {
            skipWs();
            items.add(value(depth));
            skipWs();
            if (atEnd()) throw new WireParseError("unexpected end of message");
            char c = s.charAt(i);
            if (c == ',') { i++; }
            else if (c == ']') { i++; return items; }
            else throw new WireParseError("expected ',' or ']'");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) throw new WireParseError("invalid JSON");
        i += word.length();
    }

    private String string() {
        i++; // opening '"'
        int start = i;
        StringBuilder out = null;
        while (true) {
            if (atEnd()) throw new WireParseError("unterminated string");
            char c = s.charAt(i);
            if (c == '"') {
                String result = out != null ? out.toString() : s.substring(start, i);
                i++;
                return result;
            } else if (c == '\\') {
                if (out == null) {
                    out = new StringBuilder(i - start + 16);
                    out.append(s, start, i);
                }
                escape(out);
            } else if (c < ' ') {
                // RFC 8259: control characters MUST be escaped.
                throw new WireParseError("unescaped control character");
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))
                    throw new WireParseError("unpaired surrogate");
                if (out != null) out.append(c).append(s.charAt(i + 1));
                i += 2;
            } else if (Character.isLowSurrogate(c)) {
                throw new WireParseError("unpaired surrogate");
            } else {
                if (out != null) out.append(c);
                i++;
            }
        }
    }

    private void escape(StringBuilder out) {
        if (i + 1 >= s.length()) throw new WireParseError("unterminated string");
        char e = s.charAt(i + 1);
        switch (e) {
            case '"': case '\\': case '/': out.append(e); i += 2; break;
            case 'b': out.append('\b'); i += 2; break;
            case 'f': out.append('\f'); i += 2; break;
            case 'n': out.append('\n'); i += 2; break;
            case 'r': out.append('\r'); i += 2; break;
            case 't': out.append('\t'); i += 2; break;
            case 'u': {
                char hi = hex4(i + 2);
                i += 6;
                if (Character.isHighSurrogate(hi)) {
                    // SPEC 4.1: a lone surrogate escape is not a scalar value.
                    if (i + 1 >= s.length() || s.charAt(i) != '\\' || s.charAt(i + 1) != 'u')
                        throw new WireParseError("unpaired surrogate escape");
                    char lo = hex4(i + 2);
                    if (!Character.isLowSurrogate(lo))
                        throw new WireParseError("unpaired surrogate escape");
                    i += 6;
                    out.append(hi).append(lo);
                } else if (Character.isLowSurrogate(hi)) {
                    throw new WireParseError("unpaired surrogate escape");
                } else {
                    out.append(hi);
                }
                break;
            }
            default: throw new WireParseError("invalid escape");
        }
    }

    private char hex4(int at) {
        if (at + 4 > s.length()) throw new WireParseError("unterminated string");
        int v = 0;
        for (int k = at; k < at + 4; k++) {
            // RFC 8259 HEXDIG is ASCII-only; Character.digit is Unicode-aware
            // and would accept fullwidth digits.
            char ch = s.charAt(k);
            int d;
            if (ch >= '0' && ch <= '9') d = ch - '0';
            else if (ch >= 'a' && ch <= 'f') d = ch - 'a' + 10;
            else if (ch >= 'A' && ch <= 'F') d = ch - 'A' + 10;
            else throw new WireParseError("invalid escape");
            v = (v << 4) | d;
        }
        return (char) v;
    }

    private Json.Num number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        // int part: 0, or [1-9][0-9]* — leading zeros are not JSON.
        if (atEnd()) throw new WireParseError("invalid number");
        char c = s.charAt(i);
        if (c == '0') {
            i++;
            if (!atEnd() && isDigit(s.charAt(i))) throw new WireParseError("invalid number");
        } else if (c >= '1' && c <= '9') {
            while (!atEnd() && isDigit(s.charAt(i))) i++;
        } else {
            throw new WireParseError("invalid number");
        }
        boolean integral = true;
        if (!atEnd() && s.charAt(i) == '.') {
            integral = false;
            i++;
            if (atEnd() || !isDigit(s.charAt(i))) throw new WireParseError("invalid number");
            while (!atEnd() && isDigit(s.charAt(i))) i++;
        }
        if (!atEnd() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            integral = false;
            i++;
            if (!atEnd() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            if (atEnd() || !isDigit(s.charAt(i))) throw new WireParseError("invalid number");
            while (!atEnd() && isDigit(s.charAt(i))) i++;
        }
        String text = s.substring(start, i);
        if (integral) {
            // SPEC 4.2: integers live in +/-(2^53 - 1); overflow is a refusal.
            long v;
            try {
                v = Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new WireParseError("integer out of range");
            }
            if (v > Json.MAX_SAFE_INTEGER || v < -Json.MAX_SAFE_INTEGER)
                throw new WireParseError("integer out of range");
            return Json.Num.of(v);
        } else {
            double v = Double.parseDouble(text);
            // SPEC 4.2: a literal that overflows binary64 is refused; one that
            // underflows to zero is accepted as 0.0 (amendment #99).
            if (Double.isNaN(v) || Double.isInfinite(v))
                throw new WireParseError("number out of range");
            return Json.Num.of(v);
        }
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
}
