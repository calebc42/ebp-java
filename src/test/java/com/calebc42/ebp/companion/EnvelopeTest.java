// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.calebc42.ebp.companion.engine.Vocabulary;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.json.JsonParser;
import com.calebc42.ebp.companion.rpc.Envelope;
import com.calebc42.ebp.companion.rpc.MethodRegistry;
import com.calebc42.ebp.companion.rpc.SessionState;

import java.util.Map;

import org.junit.Test;

public class EnvelopeTest {

    @Test public void classification() {
        assertEquals(Envelope.MessageClass.REQUEST, Envelope.classify(
            Envelope.request(new Json.Str("r1"), "session.ready", new Json.Obj())));
        assertEquals(Envelope.MessageClass.NOTIFICATION, Envelope.classify(
            Envelope.notification("state.changed", new Json.Obj())));
        assertEquals(Envelope.MessageClass.RESPONSE, Envelope.classify(
            Envelope.result(new Json.Str("r1"), new Json.Obj())));
        assertEquals(Envelope.MessageClass.RESPONSE, Envelope.classify(
            Envelope.error(new Json.Str("r1"), 1204, "no", "session-state")));
        assertNull(Envelope.classify((Json.Obj) JsonParser.parse("{\"jsonrpc\":\"1.0\",\"method\":\"x\"}")));
        assertNull(Envelope.classify((Json.Obj) JsonParser.parse("{\"jsonrpc\":2.0,\"method\":\"x\"}")));
        assertNull(Envelope.classify((Json.Obj) JsonParser.parse("{\"jsonrpc\":\"2.0\",\"method\":5,\"id\":1}")));
        assertNull(Envelope.classify((Json.Obj) JsonParser.parse(
            "{\"jsonrpc\":\"2.0\",\"id\":\"r1\",\"result\":{},\"error\":{}}")));
    }

    @Test public void requestIdGrammar() {
        assertTrue(Envelope.isValidRequestId(new Json.Str("r1")));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append('a');
        assertTrue(Envelope.isValidRequestId(new Json.Str(sb.toString())));
        assertFalse(Envelope.isValidRequestId(new Json.Str(sb.toString() + "a")));
        assertFalse(Envelope.isValidRequestId(new Json.Str("")));
        assertTrue(Envelope.isValidRequestId(Json.Num.of(7)));
        assertTrue(Envelope.isValidRequestId(Json.Num.of(9007199254740991L)));
        assertFalse(Envelope.isValidRequestId(Json.Num.of(7.5)));
        assertFalse(Envelope.isValidRequestId(Json.Null.NULL));
        assertFalse(Envelope.isValidRequestId(null));
    }

    @Test public void registryMatchesContract() {
        // The SPEC 11 registry and contract.json `methods` must agree.
        assertEquals(Vocabulary.METHOD_TABLE.keySet(), MethodRegistry.methods());
        for (Map.Entry<String, String> e : Vocabulary.METHOD_TABLE.entrySet()) {
            String[] parts = e.getValue().split("\\|");
            MethodRegistry.Spec spec = MethodRegistry.lookup(e.getKey());
            String sender = spec.sender == MethodRegistry.Sender.EMACS ? "emacs"
                : spec.sender == MethodRegistry.Sender.COMPANION ? "companion" : "either";
            assertEquals(e.getKey(), parts[0], sender);
            assertEquals(e.getKey(), parts[1], spec.isRequest ? "request" : "notification");
            String[] states = parts[2].split(",");
            assertEquals(e.getKey(), states.length, spec.states.size());
            for (String s : states) assertTrue(e.getKey(), spec.states.contains(SessionState.valueOf(s)));
        }
    }

    @Test public void errorKindsMatchContract() {
        assertEquals("parse-error", Vocabulary.ERROR_KINDS.get(Integer.valueOf(-32700)));
        assertEquals("content-invalid", Vocabulary.ERROR_KINDS.get(Integer.valueOf(1201)));
        assertEquals("queue-full", Vocabulary.ERROR_KINDS.get(Integer.valueOf(1601)));
        Json.Obj e = Envelope.error(new Json.Str("x"), 1201, "Invalid content", "content-invalid",
            new Json.Obj().put("path", "spec"));
        assertEquals("content-invalid", e.object("error").object("data").string("kind"));
        assertEquals("spec", e.object("error").object("data").string("path"));
    }
}
