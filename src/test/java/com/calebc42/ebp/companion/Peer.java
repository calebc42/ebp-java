// SPDX-License-Identifier: GPL-3.0-or-later
// A minimal in-process Emacs peer for engine tests: frames requests into
// the engine, decodes what the engine emits, and performs the SPEC 9
// handshake with the known-answer construction.
package com.calebc42.ebp.companion;

import com.calebc42.ebp.companion.auth.Auth;
import com.calebc42.ebp.companion.engine.Companion;
import com.calebc42.ebp.companion.engine.CompanionEngine;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.rpc.Envelope;
import com.calebc42.ebp.companion.wire.FrameDecoder;
import com.calebc42.ebp.companion.wire.FrameEncoder;

import java.util.ArrayList;
import java.util.List;

final class Peer {
    final CompanionEngine engine;
    final List<Json.Obj> inbox = new ArrayList<Json.Obj>();
    final List<byte[]> rawFrames = new ArrayList<byte[]>();
    private final FrameDecoder decoder = new FrameDecoder();
    private int nextId = 1;
    boolean sinkFails = false;

    Peer(Companion companion) {
        engine = companion.open(new CompanionEngine.Sink() {
            @Override public void write(byte[] frame) throws java.io.IOException {
                if (sinkFails) throw new java.io.IOException("sink closed");
                rawFrames.add(frame);
                decoder.feed(frame, new FrameDecoder.Consumer() {
                    @Override public void accept(Json.Obj message) { inbox.add(message); }
                });
            }
        });
    }

    void sendRaw(byte[] bytes) { engine.feed(bytes, 0, bytes.length); }

    void send(Json.Obj msg) { sendRaw(FrameEncoder.encode(Json.write(msg))); }

    /** Send a request and return its response (the engine answers inline). */
    Json.Obj request(String method, Json.Obj params) {
        String id = "r" + (nextId++);
        int before = inbox.size();
        send(Envelope.request(new Json.Str(id), method, params));
        for (int i = before; i < inbox.size(); i++) {
            Json.Obj m = inbox.get(i);
            if (m.has("id") && !m.has("method") && id.equals(m.string("id"))) return m;
        }
        return null;
    }

    Json.Obj result(String method, Json.Obj params) {
        Json.Obj r = request(method, params);
        if (r == null) throw new AssertionError("no response to " + method);
        if (r.has("error")) throw new AssertionError(method + " failed: " + Json.write(r));
        return r.object("result");
    }

    Json.Obj error(String method, Json.Obj params) {
        Json.Obj r = request(method, params);
        if (r == null) throw new AssertionError("no response to " + method);
        if (!r.has("error")) throw new AssertionError(method + " unexpectedly succeeded: " + Json.write(r));
        return r.object("error");
    }

    /** Companion-originated requests received so far, oldest first. */
    List<Json.Obj> requestsFrom(String method) {
        List<Json.Obj> out = new ArrayList<Json.Obj>();
        for (Json.Obj m : inbox)
            if (m.has("id") && method.equals(m.string("method"))) out.add(m);
        return out;
    }

    List<Json.Obj> notifications(String method) {
        List<Json.Obj> out = new ArrayList<Json.Obj>();
        for (Json.Obj m : inbox)
            if (!m.has("id") && method.equals(m.string("method"))) out.add(m);
        return out;
    }

    void respond(Json.Obj request, Json.Obj result) {
        send(Envelope.result(request.get("id"), result));
    }

    void respondError(Json.Obj request, int code, String kind) {
        send(Envelope.error(request.get("id"), code, "error", kind));
    }

    Json.Obj hello(String pairingId, String clientNonce, List<String> wants) {
        Json.Arr w = new Json.Arr();
        for (String s : wants) w.add(s);
        return new Json.Obj().put("protocol", 3)
            .put("client", new Json.Obj().put("name", "test-emacs").put("version", "0.0.1"))
            .put("pairing_id", pairingId).put("client_nonce", clientNonce).put("wants", w);
    }

    /** Full handshake to SYNCING; returns the welcome result. */
    Json.Obj handshake(String pairingId, byte[] token, List<String> wants) {
        String cn = "202122232425262728292a2b2c2d2e2f";
        Json.Obj nonce = result("session.hello", hello(pairingId, cn, wants));
        String sn = nonce.string("server_nonce");
        Json.Obj auth = new Json.Obj().put("pairing_id", pairingId).put("client_nonce", cn)
            .put("server_nonce", sn).put("client_proof", Auth.clientProof(token, pairingId, cn, sn));
        Json.Obj welcome = result("auth.response", auth);
        if (!Auth.verifyServerProof(welcome.string("server_proof"), token, pairingId, cn, sn))
            throw new AssertionError("server proof did not verify");
        return welcome;
    }

    Json.Obj replay() { return result("queue.replay", new Json.Obj()); }

    void ready() { result("session.ready", new Json.Obj()); }

    Json.Obj update(String surface, long revision, Json.Obj spec) {
        return result("surface.update", new Json.Obj().put("surface", surface).put("revision", revision).put("spec", spec));
    }
}
