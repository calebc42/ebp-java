// SPDX-License-Identifier: GPL-3.0-or-later
// The Java twin of ebp.el's wire suite and ebp-kmp's WireConformanceTest:
// every goldens/wire fixture at whole/1-octet/7-octet chunkings against the
// manifest's expected outcome (SPEC 24.5-24.6), plus encoder byte syntax.
package com.calebc42.ebp.companion;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.json.JsonParser;
import com.calebc42.ebp.companion.wire.FrameClose;
import com.calebc42.ebp.companion.wire.FrameDecoder;
import com.calebc42.ebp.companion.wire.FrameEncoder;
import com.calebc42.ebp.companion.wire.FrameIncomplete;
import com.calebc42.ebp.companion.wire.InvalidRequest;
import com.calebc42.ebp.companion.wire.WireParseError;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class WireConformanceTest {

    private final File ebpDir = new File(System.getProperty("ebp.dir", "../ebp"));
    private final File wireDir = new File(ebpDir, "goldens/wire");

    private static Map<String, List<byte[]>> chunkings(byte[] bytes) {
        Map<String, List<byte[]>> out = new LinkedHashMap<String, List<byte[]>>();
        List<byte[]> whole = new ArrayList<byte[]>();
        whole.add(bytes);
        out.put("whole", whole);
        List<byte[]> one = new ArrayList<byte[]>();
        for (byte b : bytes) one.add(new byte[] {b});
        out.put("1-octet", one);
        List<byte[]> seven = new ArrayList<byte[]>();
        for (int i = 0; i < bytes.length; i += 7) {
            int n = Math.min(7, bytes.length - i);
            byte[] c = new byte[n];
            System.arraycopy(bytes, i, c, 0, n);
            seven.add(c);
        }
        out.put("7-octet", seven);
        return out;
    }

    /** {messages, error} — one of the two is null. */
    private static Object[] run(List<byte[]> chunks) {
        FrameDecoder decoder = new FrameDecoder();
        final List<Json.Obj> messages = new ArrayList<Json.Obj>();
        try {
            for (byte[] c : chunks)
                decoder.feed(c, new FrameDecoder.Consumer() {
                    @Override public void accept(Json.Obj m) { messages.add(m); }
                });
            decoder.finish();
            return new Object[] {messages, null};
        } catch (FrameClose e) { return new Object[] {null, "close"}; }
        catch (FrameIncomplete e) { return new Object[] {null, "incomplete-frame"}; }
        catch (WireParseError e) { return new Object[] {null, "parse-error"}; }
        catch (InvalidRequest e) { return new Object[] {null, "invalid-request"}; }
    }

    @Test public void wireGoldensBehavePerManifestAtAllChunkings() throws Exception {
        String manifestText = new String(Files.readAllBytes(new File(wireDir, "manifest.json").toPath()), StandardCharsets.UTF_8);
        Json.Obj manifest = (Json.Obj) JsonParser.parse(manifestText);
        Json.Arr fixtures = manifest.array("fixtures");
        assertTrue("adversarial set truncated?", fixtures.size() >= 10);
        for (int f = 0; f < fixtures.size(); f++) {
            Json.Obj fx = fixtures.get(f).asObject();
            // SPEC 24.5: the Companion is never the excused role.
            Json.Arr roles = fx.array("roles");
            if (roles != null) {
                boolean companion = false;
                for (int i = 0; i < roles.size(); i++) if ("companion".equals(roles.get(i).stringOrNull())) companion = true;
                assertTrue(fx.string("file") + ": the Companion role is never excused", companion);
            }
            byte[] bytes = Files.readAllBytes(new File(wireDir, fx.string("file")).toPath());
            for (Map.Entry<String, List<byte[]>> e : chunkings(bytes).entrySet()) {
                Object[] r = run(e.getValue());
                String context = fx.string("file") + " [" + e.getKey() + "]";
                if ("positive".equals(fx.string("kind"))) {
                    assertNull(context + ": unexpected error " + r[1], r[1]);
                    @SuppressWarnings("unchecked") List<Json.Obj> messages = (List<Json.Obj>) r[0];
                    Json.Arr expected = fx.array("expect_messages");
                    assertEquals(context, expected.size(), messages.size());
                    for (int i = 0; i < messages.size(); i++)
                        assertTrue(context + " message " + i + " differs", Json.structurallyEqual(messages.get(i), expected.get(i)));
                } else {
                    assertEquals(context, fx.string("expect_error"), r[1]);
                }
            }
        }
    }

    @Test public void utf8FixtureWitnessesOctetCounting() throws Exception {
        byte[] bytes = Files.readAllBytes(new File(wireDir, "03-utf8-length.bin").toPath());
        String latin = new String(bytes, StandardCharsets.ISO_8859_1);
        int headerEnd = latin.indexOf("\r\n\r\n") + 4;
        int declared = Integer.parseInt(latin.substring(latin.indexOf("Content-Length: ") + 16, latin.indexOf("\r\n")));
        byte[] body = new byte[bytes.length - headerEnd];
        System.arraycopy(bytes, headerEnd, body, 0, body.length);
        String text = new String(body, StandardCharsets.UTF_8);
        assertEquals(declared, body.length);
        assertTrue(declared != text.length());
        // Re-framing the decoded text reproduces the fixture byte-exactly.
        assertArrayEquals(bytes, FrameEncoder.encode(text));
    }

    @Test public void encoderSyntaxIsExact() {
        assertArrayEquals("Content-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII), FrameEncoder.encode("{}"));
        // 9 characters, 10 octets: length counts UTF-8 octets, not chars.
        byte[] frame = FrameEncoder.encode("{\"a\":\"é\"}");
        assertTrue(new String(frame, StandardCharsets.UTF_8).startsWith("Content-Length: 10\r\n\r\n"));
    }

    @Test public void pipelinedFramesDecodeInOrderAtEverySplitPoint() {
        byte[] a = FrameEncoder.encode("{\"jsonrpc\":\"2.0\",\"method\":\"log.debug\",\"params\":{\"m\":\"first\"}}");
        byte[] b = FrameEncoder.encode("{\"jsonrpc\":\"2.0\",\"method\":\"log.debug\",\"params\":{\"m\":\"second\"}}");
        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        for (int cut = 1; cut < joined.length; cut++) {
            FrameDecoder d = new FrameDecoder();
            List<Json.Obj> out = new ArrayList<Json.Obj>();
            byte[] first = new byte[cut], second = new byte[joined.length - cut];
            System.arraycopy(joined, 0, first, 0, cut);
            System.arraycopy(joined, cut, second, 0, second.length);
            out.addAll(d.feed(first));
            out.addAll(d.feed(second));
            d.finish();
            assertEquals("cut at " + cut, 2, out.size());
            assertEquals("first", out.get(0).object("params").string("m"));
            assertEquals("second", out.get(1).object("params").string("m"));
        }
    }

    @Test public void largeBodyInEightKiBChunksDecodesOnce() {
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < 4000000; i++) payload.append('x');
        byte[] encoded = FrameEncoder.encode("{\"jsonrpc\":\"2.0\",\"method\":\"log.debug\",\"params\":{\"m\":\"" + payload + "\"}}");
        FrameDecoder d = new FrameDecoder();
        final List<Json.Obj> out = new ArrayList<Json.Obj>();
        for (int i = 0; i < encoded.length; i += 8192) {
            int n = Math.min(8192, encoded.length - i);
            d.feed(encoded, i, n, new FrameDecoder.Consumer() {
                @Override public void accept(Json.Obj m) { out.add(m); }
            });
        }
        d.finish();
        assertEquals(1, out.size());
        assertEquals(4000000, out.get(0).object("params").string("m").length());
    }

    @Test public void goodFrameAheadOfBadOneIsDelivered() {
        byte[] good = FrameEncoder.encode("{\"jsonrpc\":\"2.0\",\"method\":\"log.debug\",\"params\":{}}");
        byte[] bad = FrameEncoder.encode("{not json");
        byte[] joined = new byte[good.length + bad.length];
        System.arraycopy(good, 0, joined, 0, good.length);
        System.arraycopy(bad, 0, joined, good.length, bad.length);
        FrameDecoder d = new FrameDecoder();
        final List<Json.Obj> out = new ArrayList<Json.Obj>();
        try {
            d.feed(joined, new FrameDecoder.Consumer() {
                @Override public void accept(Json.Obj m) { out.add(m); }
            });
            assertTrue(false);
        } catch (WireParseError expected) {
            assertEquals(1, out.size());
        }
        assertEquals(0, d.pendingOctets()); // the stream is synchronized after the fault
    }
}
