// SPDX-License-Identifier: GPL-3.0-or-later
// A small pure-Java rasterizer for the five SPEC 17.5 op shapes, used by
// the host runner and the host tests.  Fidelity is deliberately modest:
// axis-aligned fills and one-pixel strokes are exact; the `text` op draws
// a baseline rule instead of glyphs (no font machinery on the host); the
// Android host renders real text through android.graphics.
package com.calebc42.ebp.companion.host;

import com.calebc42.ebp.companion.engine.Colors;
import com.calebc42.ebp.companion.engine.Rasterizer;
import com.calebc42.ebp.companion.json.Json;

import java.util.ArrayList;
import java.util.List;

public final class SoftwareRasterizer implements Rasterizer {

    @Override public int[] rasterize(Json.Obj node, int width, int height) {
        int[] px = new int[width * height];
        Json.Arr ops = node.array("ops");
        if (ops == null) return px;
        for (int i = 0; i < ops.size(); i++) {
            Json.Value v = ops.get(i);
            if (!(v instanceof Json.Obj)) continue;
            Json.Obj op = (Json.Obj) v;
            String kind = op.stringOr("op", "");
            try {
                if (kind.equals("rect")) rect(px, width, height, op);
                else if (kind.equals("line")) line(px, width, height, op);
                else if (kind.equals("circle")) circle(px, width, height, op);
                else if (kind.equals("path")) path(px, width, height, op);
                else if (kind.equals("text")) text(px, width, height, op);
                // SPEC 17.5: unknown ops are skipped, never executed.
            } catch (RuntimeException e) {
                // A malformed op that passed validation cannot occur; skip.
            }
        }
        return px;
    }

    /** SPEC 16.6 Color via the shared parser. */
    public static int color(String s, int fallback) { return Colors.parse(s, fallback); }

    private static int num(Json.Obj op, String m, int d) {
        Double v = op.number(m);
        return v == null ? d : (int) Math.round(v.doubleValue());
    }

    private static void put(int[] px, int w, int h, int x, int y, int argb) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        px[y * w + x] = argb;
    }

    private static void fillRect(int[] px, int w, int h, int x0, int y0, int x1, int y1, int argb) {
        for (int y = Math.max(0, y0); y < Math.min(h, y1); y++)
            for (int x = Math.max(0, x0); x < Math.min(w, x1); x++)
                px[y * w + x] = argb;
    }

    private static void rect(int[] px, int w, int h, Json.Obj op) {
        int x = num(op, "x", 0), y = num(op, "y", 0);
        int rw = num(op, "width", 0), rh = num(op, "height", 0);
        if (op.has("fill")) fillRect(px, w, h, x, y, x + rw, y + rh, color(op.string("fill"), 0xFF000000));
        if (op.has("color")) {
            int c = color(op.string("color"), 0xFF000000);
            int sw = Math.max(1, num(op, "stroke_width", 1));
            fillRect(px, w, h, x, y, x + rw, y + sw, c);
            fillRect(px, w, h, x, y + rh - sw, x + rw, y + rh, c);
            fillRect(px, w, h, x, y, x + sw, y + rh, c);
            fillRect(px, w, h, x + rw - sw, y, x + rw, y + rh, c);
        }
    }

    private static void segment(int[] px, int w, int h, int x0, int y0, int x1, int y1, int argb, int width) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int half = Math.max(0, (width - 1) / 2);
        while (true) {
            for (int ox = -half; ox <= half; ox++)
                for (int oy = -half; oy <= half; oy++)
                    put(px, w, h, x0 + ox, y0 + oy, argb);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private static void line(int[] px, int w, int h, Json.Obj op) {
        segment(px, w, h, num(op, "x1", 0), num(op, "y1", 0), num(op, "x2", 0), num(op, "y2", 0),
            color(op.string("color"), 0xFF000000), Math.max(1, num(op, "width", 1)));
    }

    private static void circle(int[] px, int w, int h, Json.Obj op) {
        int cx = num(op, "cx", 0), cy = num(op, "cy", 0), r = num(op, "radius", 0);
        boolean fill = op.has("fill");
        int fc = color(op.string("fill"), 0xFF000000);
        boolean stroke = op.has("color");
        int sc = color(op.string("color"), 0xFF000000);
        int sw = Math.max(1, num(op, "stroke_width", 1));
        long rr = (long) r * r, inner = (long) Math.max(0, r - sw) * Math.max(0, r - sw);
        for (int y = cy - r; y <= cy + r; y++)
            for (int x = cx - r; x <= cx + r; x++) {
                long d = (long) (x - cx) * (x - cx) + (long) (y - cy) * (y - cy);
                if (d > rr) continue;
                if (stroke && d >= inner) put(px, w, h, x, y, sc);
                else if (fill) put(px, w, h, x, y, fc);
            }
    }

    private static void path(int[] px, int w, int h, Json.Obj op) {
        Json.Arr pts = op.array("points");
        if (pts == null) return;
        List<int[]> p = new ArrayList<int[]>();
        for (int i = 0; i < pts.size(); i++) {
            Json.Obj pt = (Json.Obj) pts.get(i);
            p.add(new int[] {num(pt, "x", 0), num(pt, "y", 0)});
        }
        if (p.size() < 2) return;
        boolean closed = op.boolOr("closed", false) || op.has("fill");
        if (op.has("fill") && p.size() >= 3) {
            int fc = color(op.string("fill"), 0xFF000000);
            // Even-odd scanline fill.
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (int[] q : p) { minY = Math.min(minY, q[1]); maxY = Math.max(maxY, q[1]); }
            for (int y = Math.max(0, minY); y <= Math.min(h - 1, maxY); y++) {
                List<Integer> xs = new ArrayList<Integer>();
                for (int i = 0; i < p.size(); i++) {
                    int[] a = p.get(i), b = p.get((i + 1) % p.size());
                    if ((a[1] <= y && b[1] > y) || (b[1] <= y && a[1] > y)) {
                        double t = (y - a[1]) / (double) (b[1] - a[1]);
                        xs.add(Integer.valueOf((int) Math.round(a[0] + t * (b[0] - a[0]))));
                    }
                }
                java.util.Collections.sort(xs);
                for (int i = 0; i + 1 < xs.size(); i += 2)
                    fillRect(px, w, h, xs.get(i).intValue(), y, xs.get(i + 1).intValue() + 1, y + 1, fc);
            }
        }
        if (op.has("color")) {
            int sc = color(op.string("color"), 0xFF000000);
            int sw = Math.max(1, num(op, "stroke_width", 1));
            int n = closed ? p.size() : p.size() - 1;
            for (int i = 0; i < n; i++) {
                int[] a = p.get(i), b = p.get((i + 1) % p.size());
                segment(px, w, h, a[0], a[1], b[0], b[1], sc, sw);
            }
        }
    }

    private static void text(int[] px, int w, int h, Json.Obj op) {
        // No glyphs on the host: a baseline rule the length of the text at
        // the requested size stands in, so layout is still observable.
        String s = op.stringOr("text", "");
        int size = Math.max(1, num(op, "size", 14));
        int x = num(op, "x", 0), y = num(op, "y", 0);
        int c = color(op.string("color"), 0xFF000000);
        int len = (int) Math.round(s.length() * size * 0.6);
        fillRect(px, w, h, x, y, x + len, y + 1, c);
    }
}
