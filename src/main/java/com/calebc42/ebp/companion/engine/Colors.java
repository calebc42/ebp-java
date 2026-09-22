// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.engine;

/** SPEC 16.6 Color parsing shared by every rasterizer: `#rgb`, `#rgba`,
 * `#rrggbb`, `#rrggbbaa` (hex digits case-insensitive), or a theme-role
 * identifier, which resolves to a legible fallback and is never made
 * transparent. */
public final class Colors {
    private Colors() {}

    public static final int ROLE_FALLBACK = 0xFF808080;

    /** ARGB32, non-premultiplied. */
    public static int parse(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        if (s.charAt(0) != '#') return ROLE_FALLBACK;
        String h = s.substring(1);
        try {
            switch (h.length()) {
                case 3: return 0xFF000000 | (dup(h, 0) << 16) | (dup(h, 1) << 8) | dup(h, 2);
                case 4: return (dup(h, 3) << 24) | (dup(h, 0) << 16) | (dup(h, 1) << 8) | dup(h, 2);
                case 6: return 0xFF000000 | Integer.parseInt(h, 16);
                case 8: return (int) Long.parseLong(h.substring(6, 8) + h.substring(0, 6), 16);
                default: return fallback;
            }
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int dup(String h, int i) {
        int d = Character.digit(h.charAt(i), 16);
        if (d < 0) throw new NumberFormatException(h);
        return d * 17;
    }
}
