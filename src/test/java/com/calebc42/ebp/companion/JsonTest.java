// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.json.JsonParser;
import com.calebc42.ebp.companion.wire.InvalidRequest;
import com.calebc42.ebp.companion.wire.WireParseError;

import org.junit.Test;

public class JsonTest {

    private static Json.Value parse(String s) { return JsonParser.parse(s); }

    private static void parseError(String s) {
        try { parse(s); fail("accepted " + s); } catch (WireParseError expected) { /* ok */ }
    }

    @Test public void grammarIsStrict() {
        parseError("{\"a\":01}");
        parseError("{'a':1}");
        parseError("{a:1}");
        parseError("[1,]");
        parseError("{\"a\":1,}");
        parseError("{\"a\":NaN}");
        parseError("{\"a\":Infinity}");
        parseError("{\"a\":0x10}");
        parseError("{\"a\":1} x");
        parseError("{\"a\":\"\t\"}");
        parseError("");
    }

    @Test public void duplicateMembersAreInvalidRequest() {
        try { parse("{\"a\":1,\"a\":2}"); fail(); } catch (InvalidRequest expected) { /* ok */ }
        // Semantic comparison after escape decoding: \u0061 is "a".
        try { parse("{\"a\":1,\"\\u0061\":2}"); fail(); } catch (InvalidRequest expected) { /* ok */ }
        assertTrue(parse("{\"a\":1,\"b\":{\"a\":2}}").isObject());
        assertTrue(parse("{\"a\":[{\"x\":1},{\"x\":2}]}").isObject());
    }

    @Test public void integerRangeAndSpelling() {
        assertEquals(Long.valueOf(9007199254740991L), parse("9007199254740991").integerOrNull());
        parseError("9007199254740992");
        parseError("-9007199254740992");
        assertNull(parse("7.0").integerOrNull());
        assertEquals(Long.valueOf(7), parse("7.0").integralOrNull());
        assertEquals(Long.valueOf(60), parse("6e1").integralOrNull());
        assertNull(parse("\"7\"").integerOrNull());
        parseError("1e400");
        assertEquals(0.0, parse("1e-400").asDouble(), 0.0); // underflow accepted as zero
    }

    @Test public void surrogates() {
        parseError("\"\\ud83d\"");
        parseError("\"\\ude00\"");
        parseError("\"\\ud83dx\"");
        assertEquals("\ud83d\ude00", parse("\"\\ud83d\\ude00\"").asString());
        assertEquals("\ud83d\ude00", parse("\"\ud83d\ude00\"").asString());
        parseError("\"\\u\uff10\uff10\uff12\uff12\""); // fullwidth digits are not HEXDIG
    }

    @Test public void depthLimit() {
        StringBuilder ok = new StringBuilder(), bad = new StringBuilder();
        for (int i = 0; i < 64; i++) ok.append('[');
        for (int i = 0; i < 64; i++) ok.append(']');
        for (int i = 0; i < 65; i++) bad.append('[');
        for (int i = 0; i < 65; i++) bad.append(']');
        assertTrue(parse(ok.toString()).isArray());
        parseError(bad.toString());
        assertTrue(JsonParser.parse(bad.toString(), 512).isArray());
    }

    @Test public void structuralEquality() {
        assertEquals(parse("1"), parse("1.0"));
        assertEquals(parse("1"), parse("1e0"));
        assertEquals(parse("-0"), parse("0"));
        assertFalse(parse("1").equals(parse("\"1\"")));
        assertFalse(parse("true").equals(parse("1")));
        assertEquals(parse("{\"a\":1,\"b\":[1,2]}"), parse("{\"b\":[1.0,2],\"a\":1}"));
        assertFalse(parse("[1,2]").equals(parse("[2,1]")));
        assertEquals(parse("{\"a\":1}").hashCode(), parse("{\"a\":1.0}").hashCode());
    }

    @Test public void writerRoundTrips() {
        String[] texts = {"{\"a\":[1,2.5,\"x\\ny\",true,null,{}]}", "\"caf\u00e9 \u2615\"", "{\"k\":\"\\u0001\"}"};
        for (String t : texts) {
            Json.Value v = parse(t);
            assertEquals(v, parse(Json.write(v)));
        }
        assertEquals("{\"a\":\"\\u0001\"}", Json.write(parse("{\"a\":\"\\u0001\"}")));
        assertEquals("\"caf\u00e9\"", Json.write(new Json.Str("caf\u00e9")));
    }
}
