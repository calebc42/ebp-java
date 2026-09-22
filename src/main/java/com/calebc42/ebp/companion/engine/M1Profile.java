// SPDX-License-Identifier: GPL-3.0-or-later
// The M1 advertisement, shared by the host runner and the Android host:
// exactly the implemented vocabulary (three of the eight Core Node Set
// types plus the optional canvas type, the two REQUIRED app builtins), a
// strict `members` map so no optional member is implied, and the welcome
// limits.  This is an experimental partial endpoint; see README's
// compatibility table.
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.json.Json;

public final class M1Profile {
    private M1Profile() {}

    public static Json.Obj surfaceProfiles() {
        Json.Obj members = new Json.Obj()
            .put("universal", new Json.Arr().add("id").add("key"))
            .put("nodes", new Json.Obj()
                .put("text", new Json.Arr().add("text").add("style"))
                .put("column", new Json.Arr().add("children").add("spacing"))
                .put("button", new Json.Arr().add("label").add("on_tap").add("enabled"))
                .put("canvas", new Json.Arr().add("width").add("height").add("ops")))
            .put("semantics", new Json.Arr())
            .put("surface", new Json.Arr().add("views").add("initial_view"));
        Json.Obj app = new Json.Obj()
            .put("node_types", new Json.Arr().add("column").add("text").add("button").add("canvas"))
            .put("builtins", new Json.Arr().add("view.switch").add("companion.settings.open"))
            .put("features", new Json.Arr())
            .put("extensions", new Json.Arr())
            .put("members", members);
        return new Json.Obj().put("app", app);
    }

    public static Json.Obj limits() {
        return new Json.Obj().put("max_frame_bytes", 4194304).put("max_queued_events", 256)
            .put("max_queued_bytes", 8388608).put("max_event_bytes", 262144).put("max_surfaces", 16)
            .put("max_surface_ids", 1024).put("max_field_bytes", 65536).put("max_input_state_bytes", 262144)
            .put("max_capture_fields", 64).put("max_canvas_ops", 1024);
    }
}
