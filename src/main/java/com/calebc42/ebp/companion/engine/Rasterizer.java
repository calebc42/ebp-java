// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.json.Json;

/** Turns an accepted `canvas` node into WIDTH x HEIGHT ARGB32 pixels
 * (row-major, non-premultiplied).  The Android host uses android.graphics;
 * the host runner uses a small software rasterizer.  Unknown ops are
 * skipped and never treated as executable code (SPEC 17.5). */
public interface Rasterizer {
    int[] rasterize(Json.Obj canvasNode, int width, int height);
}
