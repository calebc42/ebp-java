// SPDX-License-Identifier: GPL-3.0-or-later
// The accepted snapshot flattened for a line-oriented renderer: text,
// buttons, and canvases in presentation order, each with its structural
// tree path as the activation identity.  Unknown or unadvertised node
// types degrade to their `children` (SPEC 16.2) and dispatch nothing.
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.store.SurfaceRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Presentation {

    public static final class Item {
        public final String kind;      // "text", "button", "canvas"
        public final String path;
        public final Json.Obj node;
        Item(String kind, String path, Json.Obj node) { this.kind = kind; this.path = path; this.node = node; }
    }

    public final String surface;
    public final boolean present;
    public final long revision;
    public final String currentView;   // null unless multi-view
    public final List<Item> items;
    private final Map<String, Json.Obj> byPath;

    private Presentation(String surface, boolean present, long revision, String currentView,
                         List<Item> items, Map<String, Json.Obj> byPath) {
        this.surface = surface; this.present = present; this.revision = revision;
        this.currentView = currentView; this.items = Collections.unmodifiableList(items);
        this.byPath = byPath;
    }

    /** The node at PATH in the presented tree, or null. */
    public Json.Obj nodeAt(String path) { return byPath.get(path); }

    public static Presentation absent(String surface, long revision) {
        return new Presentation(surface, false, revision, null, new ArrayList<Item>(), new HashMap<String, Json.Obj>());
    }

    /** Flatten RECORD's accepted snapshot for the advertised PROFILE. */
    public static Presentation of(SurfaceRecord record, Profile profile) {
        if (record == null || !record.present) {
            return absent(record == null ? "" : record.surface, record == null ? -1 : record.revision);
        }
        List<Item> items = new ArrayList<Item>();
        Map<String, Json.Obj> byPath = new HashMap<String, Json.Obj>();
        Json.Obj spec = record.spec;
        String view = null;
        if (spec.has("views")) {
            view = record.currentView != null ? record.currentView : spec.string("initial_view");
            Json.Obj views = spec.object("views");
            Json.Obj root = views == null || view == null ? null : views.object(view);
            if (root != null) flatten(root, "v:" + view, profile, items, byPath);
        } else {
            flatten(spec, "r", profile, items, byPath);
        }
        return new Presentation(record.surface, true, record.revision, view, items, byPath);
    }

    private static void flatten(Json.Obj node, String path, Profile profile, List<Item> items,
                                Map<String, Json.Obj> byPath) {
        String t = node.stringOr("t", "");
        boolean known = profile.nodeTypes.contains(t) && Vocabulary.NODE_SCHEMA.containsKey(t);
        if (known && t.equals("text")) {
            items.add(new Item("text", path, node));
            byPath.put(path, node);
        } else if (known && t.equals("button")) {
            items.add(new Item("button", path, node));
            byPath.put(path, node);
        } else if (known && t.equals("canvas")) {
            items.add(new Item("canvas", path, node));
            byPath.put(path, node);
        } else {
            // column, or an unknown/unadvertised type: a neutral vertical
            // sequence of its valid children, nothing else.
            Json.Arr children = node.array("children");
            if (children == null) return;
            for (int i = 0; i < children.size(); i++) {
                Json.Value c = children.get(i);
                if (c instanceof Json.Obj && ((Json.Obj) c).string("t") != null)
                    flatten((Json.Obj) c, path + "." + i, profile, items, byPath);
            }
        }
    }

    /** The renderer document: everything but the raster bytes, which the
     * host appends after rasterizing each canvas item in order. */
    public Json.Obj document(long generation, List<int[]> rasters) {
        Json.Obj doc = new Json.Obj().put("surface", surface).put("present", present)
            .put("revision", revision).put("generation", generation);
        if (currentView != null) doc.put("current_view", currentView);
        Json.Arr arr = new Json.Arr();
        long offset = 0;
        int canvasIndex = 0;
        for (Item item : items) {
            Json.Obj o = new Json.Obj().put("kind", item.kind).put("path", item.path);
            if (item.kind.equals("text")) {
                o.put("text", item.node.stringOr("text", ""));
                String style = item.node.string("style");
                if (style == null || !Vocabulary.ENUMS.get("text.style").contains(style)) style = "body";
                o.put("style", style);
            } else if (item.kind.equals("button")) {
                o.put("label", item.node.stringOr("label", ""));
                o.put("enabled", item.node.boolOr("enabled", true));
                String id = item.node.string("id");
                if (id != null) o.put("id", id);
            } else {
                int w = (int) Math.ceil(item.node.number("width").doubleValue());
                int h = (int) Math.ceil(item.node.number("height").doubleValue());
                int[] pixels = canvasIndex < rasters.size() ? rasters.get(canvasIndex) : null;
                canvasIndex++;
                long bytes = pixels == null ? 0 : 4L * pixels.length;
                o.put("width", w).put("height", h).put("raster_offset", offset).put("raster_bytes", bytes);
                offset += bytes;
            }
            arr.add(o);
        }
        doc.put("items", arr);
        return doc;
    }
}
