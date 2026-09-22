// SPDX-License-Identifier: GPL-3.0-or-later
// EBP 3 JSON-RPC envelope conventions.  Implements ebp/SPEC.md section 7.
package com.calebc42.ebp.companion.rpc;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.wire.WireLimits;

public final class Envelope {
    private Envelope() {}

    public enum MessageClass { REQUEST, NOTIFICATION, RESPONSE }

    /** SPEC 4.4 identifier grammar: ASCII, first char alphanumeric. */
    public static boolean isIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (!(isAlnum(c0))) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(isAlnum(c) || c == '.' || c == '_' || c == '-' || c == ':' || c == '/'))
                return false;
        }
        return true;
    }

    private static boolean isAlnum(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /** SPEC 4.4: an identifier of at most 128 octets (ASCII, so chars). */
    public static boolean isBoundedIdentifier(String s) {
        return isIdentifier(s) && s.length() <= WireLimits.MAX_IDENTIFIER_OCTETS;
    }

    /** SPEC 7.2: a string identifier of at most 64 ASCII octets, or a safe
     * integer — never null, never fractional, never a string spelling of a
     * number (a JSON string "7" and the number 7 are different ids). */
    public static boolean isValidRequestId(Json.Value id) {
        if (id == null) return false;
        if (id.isString()) {
            String s = id.asString();
            return s.length() >= 1 && s.length() <= WireLimits.MAX_REQUEST_ID_OCTETS
                && isIdentifier(s);
        }
        return id.integerOrNull() != null;
    }

    /** SPEC 11/4.5: a method name is a §4.4 identifier of at most 128
     * octets — validated BEFORE any registry consult or allocation. */
    public static boolean isValidMethodName(String method) {
        return method != null && method.length() >= 1
            && method.length() <= WireLimits.MAX_METHOD_OCTETS && isIdentifier(method);
    }

    /** The `method` member as a string, or null when absent OR not a JSON
     * string.  A non-string `method` is a malformed envelope, never a lookup. */
    public static String methodOf(Json.Obj msg) {
        return msg.string("method");
    }

    /** Classify a parsed message per SPEC 7.1, or null when structurally
     * invalid: `jsonrpc` must be the JSON STRING "2.0"; `method`, when
     * present, must be a string; id/result/error must fit exactly one class. */
    public static MessageClass classify(Json.Obj msg) {
        String version = msg.string("jsonrpc");
        if (version == null || !version.equals("2.0")) return null;
        boolean hasMethod = msg.has("method");
        if (hasMethod && methodOf(msg) == null) return null;
        boolean hasId = msg.has("id");
        boolean hasResult = msg.has("result");
        boolean hasError = msg.has("error");
        if (hasMethod && hasId && !hasResult && !hasError) return MessageClass.REQUEST;
        if (hasMethod && !hasId && !hasResult && !hasError) return MessageClass.NOTIFICATION;
        if (!hasMethod && hasId && (hasResult ^ hasError)) return MessageClass.RESPONSE;
        return null;
    }

    public static Json.Obj request(Json.Value id, String method, Json.Obj params) {
        if (!isValidRequestId(id)) throw new IllegalArgumentException("invalid request id");
        return new Json.Obj().put("jsonrpc", "2.0").put("id", id).put("method", method)
            .put("params", params);
    }

    public static Json.Obj notification(String method, Json.Obj params) {
        return new Json.Obj().put("jsonrpc", "2.0").put("method", method).put("params", params);
    }

    /** The peer's own id echoed back verbatim — string stays string, integer
     * stays integer (SPEC 7.2). */
    public static Json.Obj result(Json.Value id, Json.Obj result) {
        return new Json.Obj().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    /** SPEC 8: numeric code, concise message, `data.kind` plus optional members. */
    public static Json.Obj error(Json.Value id, int code, String message, String kind, Json.Obj data) {
        Json.Obj d = data == null ? new Json.Obj() : data;
        d = d.with("kind", new Json.Str(kind));
        Json.Obj err = new Json.Obj().put("code", code).put("message", message).put("data", d);
        return new Json.Obj().put("jsonrpc", "2.0")
            .put("id", id == null ? Json.Null.NULL : id).put("error", err);
    }

    public static Json.Obj error(Json.Value id, int code, String message, String kind) {
        return error(id, code, message, kind, null);
    }
}
