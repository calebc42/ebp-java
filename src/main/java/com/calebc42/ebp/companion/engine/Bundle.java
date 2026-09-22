// SPDX-License-Identifier: GPL-3.0-or-later
// The presentation bundle handed across the JNI boundary as one immutable
// generation: a big-endian length, the UTF-8 renderer document, then every
// canvas raster as little-endian ARGB32 (the byte layout Emacs's canvas
// `:data` unibyte string expects on arm64).  Text never crosses as a JNI
// string, so modified UTF-8 cannot be mistaken for UTF-8.
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.json.Json;

import java.util.ArrayList;
import java.util.List;

public final class Bundle {
    private Bundle() {}

    public static byte[] build(Presentation p, long generation, Rasterizer rasterizer) {
        List<int[]> rasters = new ArrayList<int[]>();
        for (Presentation.Item item : p.items) {
            if (!item.kind.equals("canvas")) continue;
            int w = (int) Math.ceil(item.node.number("width").doubleValue());
            int h = (int) Math.ceil(item.node.number("height").doubleValue());
            int[] pixels = null;
            if (rasterizer != null && w > 0 && h > 0 && (long) w * h <= 16 * 1024 * 1024) {
                try {
                    pixels = rasterizer.rasterize(item.node, w, h);
                    if (pixels != null && pixels.length != w * h) pixels = null;
                } catch (RuntimeException e) {
                    pixels = null;
                }
            }
            rasters.add(pixels == null ? new int[0] : pixels);
        }
        Json.Obj doc = p.document(generation, rasters);
        byte[] json = Json.writeUtf8(doc);
        long rasterBytes = 0;
        for (int[] r : rasters) rasterBytes += 4L * r.length;
        byte[] out = new byte[4 + json.length + (int) rasterBytes];
        out[0] = (byte) (json.length >>> 24);
        out[1] = (byte) (json.length >>> 16);
        out[2] = (byte) (json.length >>> 8);
        out[3] = (byte) json.length;
        System.arraycopy(json, 0, out, 4, json.length);
        int o = 4 + json.length;
        for (int[] r : rasters) {
            for (int px : r) {
                out[o++] = (byte) px;
                out[o++] = (byte) (px >>> 8);
                out[o++] = (byte) (px >>> 16);
                out[o++] = (byte) (px >>> 24);
            }
        }
        return out;
    }

    /** Parse a bundle back (tests and diagnostics). */
    public static Json.Obj document(byte[] bundle) {
        int n = ((bundle[0] & 0xFF) << 24) | ((bundle[1] & 0xFF) << 16) | ((bundle[2] & 0xFF) << 8) | (bundle[3] & 0xFF);
        String text = new String(bundle, 4, n, java.nio.charset.StandardCharsets.UTF_8);
        return (Json.Obj) com.calebc42.ebp.companion.json.JsonParser.parse(text);
    }
}
