// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.auth.Auth;
import com.calebc42.ebp.companion.json.Json;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Static configuration for one Companion endpoint. */
public final class CompanionConfig {
    public final String serverName;
    public final String serverVersion;
    /** pairing_id (32 hex) -> decoded 16-octet token. */
    public final Map<String, byte[]> pairings;
    /** Capabilities this Companion supports (SPEC 22.1 names). */
    public final Set<String> supportedCapabilities;
    /** surface_profiles welcome member, exactly as advertised (SPEC 10.2). */
    public final Json.Obj surfaceProfiles;
    /** The parsed `app` profile. */
    public final Profile appProfile;
    /** The welcome limits object (SPEC 4.5). */
    public final Json.Obj limits;
    /** Nonce/EventId source, injectable for tests. */
    public final Auth.RandomSource random;

    public CompanionConfig(String serverName, String serverVersion, Map<String, byte[]> pairings,
                           Set<String> supportedCapabilities, Json.Obj surfaceProfiles, Json.Obj limits,
                           Auth.RandomSource random) {
        if (serverName == null || serverName.isEmpty() || Json.utf8Length(serverName) > 128)
            throw new IllegalArgumentException("server name");
        if (serverVersion == null || serverVersion.isEmpty() || Json.utf8Length(serverVersion) > 128)
            throw new IllegalArgumentException("server version");
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.pairings = Collections.unmodifiableMap(new HashMap<String, byte[]>(pairings));
        this.supportedCapabilities = Collections.unmodifiableSet(new HashSet<String>(supportedCapabilities));
        for (String cap : supportedCapabilities)
            if (!Vocabulary.CAPABILITIES.contains(cap))
                throw new IllegalArgumentException("unknown capability " + cap);
        this.surfaceProfiles = surfaceProfiles;
        Json.Obj app = surfaceProfiles.object("app");
        if (app == null) throw new IllegalArgumentException("surface_profiles.app is required");
        for (String target : surfaceProfiles.keys()) {
            Json.Obj p = surfaceProfiles.object(target);
            if (p == null) throw new IllegalArgumentException("surface_profiles." + target + " must be an object");
            Profile.parse(target, p);
        }
        this.appProfile = Profile.parse("app", app);
        this.limits = limits;
        this.random = random == null ? Auth.SECURE_RANDOM : random;
        checkLimits();
    }

    public long limit(String name) {
        Long v = limits.integer(name);
        if (v == null) throw new IllegalStateException("limit " + name + " is not advertised");
        return v.longValue();
    }

    public long limitOr(String name, long d) {
        Long v = limits.integer(name);
        return v == null ? d : v.longValue();
    }

    /** SPEC 4.5 table floors plus the welcome reservation
     * B + max_input_state_bytes - 2 <= max_frame_bytes. */
    private void checkLimits() {
        for (String key : limits.keys()) {
            Long v = limits.integer(key);
            if (v == null || v.longValue() <= 0)
                throw new IllegalArgumentException("limits." + key + " must be a positive safe integer");
        }
        require("max_frame_bytes", 4194304, 4194304);
        require("max_queued_events", 256, Long.MAX_VALUE);
        require("max_queued_bytes", 8388608, Long.MAX_VALUE);
        require("max_event_bytes", 262144, limit("max_frame_bytes") - 256);
        require("max_surfaces", 16, Long.MAX_VALUE);
        require("max_surface_ids", 1024, Long.MAX_VALUE);
        require("max_field_bytes", 65536, limit("max_frame_bytes") - 2048);
        require("max_input_state_bytes", 262144, Long.MAX_VALUE);
        require("max_capture_fields", 64, Long.MAX_VALUE);
        if (limit("max_surfaces") > limit("max_surface_ids"))
            throw new IllegalArgumentException("max_surfaces exceeds max_surface_ids");
        if (appProfile.nodeTypes.contains("canvas") && limits.integer("max_canvas_ops") == null)
            throw new IllegalArgumentException("max_canvas_ops is required when canvas is advertised");
        // Reservation: a worst-case welcome with max_surface_ids maximal
        // entries, maximal server strings, the longest legal request id.
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 128; i++) id.append('x');
        Json.Obj surfaces = new Json.Obj();
        long maxIds = limit("max_surface_ids");
        Json.Obj sample = new Json.Obj().put("revision", Json.MAX_SAFE_INTEGER).put("present", false)
            .put("current_view", id.toString());
        long perEntry = Json.utf8Length(Json.write(sample)) + 128 + 4;
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 128; i++) name.append('n');
        Json.Arr granted = new Json.Arr();
        for (String cap : supportedCapabilities) granted.add(cap);
        Json.Obj welcome = new Json.Obj();
        StringBuilder proof = new StringBuilder();
        for (int i = 0; i < 64; i++) proof.append('0');
        welcome.put("server_proof", proof.toString()).put("protocol", 3)
            .put("server", new Json.Obj().put("name", name.toString()).put("version", name.toString()))
            .put("granted", granted).put("surface_profiles", surfaceProfiles).put("surfaces", surfaces)
            .put("queued_events", limit("max_queued_events")).put("input_state", new Json.Obj())
            .put("limits", limits);
        StringBuilder rid = new StringBuilder();
        for (int i = 0; i < 64; i++) rid.append('r');
        Json.Obj response = new Json.Obj().put("jsonrpc", "2.0").put("id", rid.toString()).put("result", welcome);
        long b = Json.utf8Length(Json.write(response)) + maxIds * perEntry;
        if (b + limit("max_input_state_bytes") - 2 > limit("max_frame_bytes"))
            throw new IllegalArgumentException("welcome reservation exceeds max_frame_bytes");
    }

    private void require(String name, long min, long max) {
        long v = limit(name);
        if (v < min || v > max) throw new IllegalArgumentException("limits." + name + " out of range");
    }
}
