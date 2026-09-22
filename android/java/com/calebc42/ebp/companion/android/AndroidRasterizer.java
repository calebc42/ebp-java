// SPDX-License-Identifier: GPL-3.0-or-later
// The five SPEC 17.5 canvas op shapes through android.graphics.  Unknown
// ops are skipped; nothing here is ever treated as executable content.
package com.calebc42.ebp.companion.android;

import com.calebc42.ebp.companion.engine.Colors;
import com.calebc42.ebp.companion.engine.Rasterizer;
import com.calebc42.ebp.companion.json.Json;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

public final class AndroidRasterizer implements Rasterizer {

    private static float num(Json.Obj op, String m, float d) {
        Double v = op.number(m);
        return v == null ? d : v.floatValue();
    }

    private static Paint fillPaint(int argb) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(argb);
        return p;
    }

    private static Paint strokePaint(int argb, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(argb);
        return p;
    }

    @Override public int[] rasterize(Json.Obj node, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Json.Arr ops = node.array("ops");
        if (ops != null) {
            for (int i = 0; i < ops.size(); i++) {
                Json.Value v = ops.get(i);
                if (!(v instanceof Json.Obj)) continue;
                Json.Obj op = (Json.Obj) v;
                String kind = op.stringOr("op", "");
                try {
                    if (kind.equals("rect")) {
                        float x = num(op, "x", 0), y = num(op, "y", 0);
                        float w = num(op, "width", 0), h = num(op, "height", 0);
                        if (op.has("fill")) canvas.drawRect(x, y, x + w, y + h, fillPaint(Colors.parse(op.string("fill"), 0xFF000000)));
                        if (op.has("color")) {
                            float sw = Math.max(1f, num(op, "stroke_width", 1));
                            canvas.drawRect(x + sw / 2, y + sw / 2, x + w - sw / 2, y + h - sw / 2,
                                strokePaint(Colors.parse(op.string("color"), 0xFF000000), sw));
                        }
                    } else if (kind.equals("line")) {
                        canvas.drawLine(num(op, "x1", 0), num(op, "y1", 0), num(op, "x2", 0), num(op, "y2", 0),
                            strokePaint(Colors.parse(op.string("color"), 0xFF000000), Math.max(1f, num(op, "width", 1))));
                    } else if (kind.equals("circle")) {
                        float cx = num(op, "cx", 0), cy = num(op, "cy", 0), r = num(op, "radius", 0);
                        if (op.has("fill")) canvas.drawCircle(cx, cy, r, fillPaint(Colors.parse(op.string("fill"), 0xFF000000)));
                        if (op.has("color"))
                            canvas.drawCircle(cx, cy, r, strokePaint(Colors.parse(op.string("color"), 0xFF000000),
                                Math.max(1f, num(op, "stroke_width", 1))));
                    } else if (kind.equals("path")) {
                        Json.Arr pts = op.array("points");
                        if (pts == null || pts.size() < 2) continue;
                        Path path = new Path();
                        for (int j = 0; j < pts.size(); j++) {
                            Json.Obj pt = (Json.Obj) pts.get(j);
                            if (j == 0) path.moveTo(num(pt, "x", 0), num(pt, "y", 0));
                            else path.lineTo(num(pt, "x", 0), num(pt, "y", 0));
                        }
                        if (op.boolOr("closed", false) || op.has("fill")) path.close();
                        if (op.has("fill")) canvas.drawPath(path, fillPaint(Colors.parse(op.string("fill"), 0xFF000000)));
                        if (op.has("color"))
                            canvas.drawPath(path, strokePaint(Colors.parse(op.string("color"), 0xFF000000),
                                Math.max(1f, num(op, "stroke_width", 1))));
                    } else if (kind.equals("text")) {
                        Paint p = fillPaint(Colors.parse(op.string("color"), 0xFF000000));
                        p.setTextSize(Math.max(1f, num(op, "size", 14)));
                        canvas.drawText(op.stringOr("text", ""), num(op, "x", 0), num(op, "y", 0), p);
                    }
                } catch (RuntimeException e) {
                    // A malformed op that passed validation cannot occur; skip.
                }
            }
        }
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();
        return pixels;
    }
}
