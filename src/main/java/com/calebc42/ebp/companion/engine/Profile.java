// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.rpc.Envelope;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One SPEC 10.2 target profile as advertised in the welcome: the positive
 * node/builtin/feature/extension vocabulary and, when present, the exact
 * `members` map (SPEC 16.2: member admission is strict when advertised).
 */
public final class Profile {
    public final String target;
    public final Set<String> nodeTypes;
    public final Set<String> builtins;
    public final Set<String> features;
    public final Set<String> extensions;
    /** null when `members` is not advertised (whole-schema support). */
    public final Members members;

    public static final class Members {
        public final Set<String> universal;
        public final Map<String, Set<String>> nodes;
        public final Set<String> semantics;
        public final Set<String> surface;
        Members(Set<String> universal, Map<String, Set<String>> nodes, Set<String> semantics, Set<String> surface) {
            this.universal = universal; this.nodes = nodes; this.semantics = semantics; this.surface = surface;
        }
    }

    private Profile(String target, Set<String> nodeTypes, Set<String> builtins, Set<String> features,
                    Set<String> extensions, Members members) {
        this.target = target; this.nodeTypes = nodeTypes; this.builtins = builtins;
        this.features = features; this.extensions = extensions; this.members = members;
    }

    /** Validate and parse the advertised profile object.  Throws
     * IllegalArgumentException on a shape the Companion must never advertise. */
    public static Profile parse(String target, Json.Obj profile) {
        for (String key : profile.keys())
            if (!Vocabulary.RENDERER_PROFILE_REQUIRED.contains(key)
                && !Vocabulary.RENDERER_PROFILE_OPTIONAL.contains(key))
                throw new IllegalArgumentException("surface_profiles." + target + " contains unknown member " + key);
        Set<String> nodeTypes = identifierSet(profile, target, "node_types", false);
        Set<String> builtins = identifierSet(profile, target, "builtins", false);
        Set<String> features = identifierSet(profile, target, "features", false);
        Set<String> extensions = identifierSet(profile, target, "extensions", true);
        Members members = null;
        if (profile.has("members")) {
            Json.Obj m = profile.object("members");
            if (m == null) throw new IllegalArgumentException("members must be an object");
            if (!m.keys().equals(Vocabulary.PROFILE_MEMBERS_KEYS))
                throw new IllegalArgumentException("members must be exactly {universal, nodes, semantics, surface}");
            Set<String> universal = identifierSet(m, target, "universal", false);
            Set<String> semantics = identifierSet(m, target, "semantics", false);
            Set<String> surface = identifierSet(m, target, "surface", false);
            Json.Obj nodes = m.object("nodes");
            if (nodes == null) throw new IllegalArgumentException("members.nodes must be an object");
            Map<String, Set<String>> nodeMembers = new HashMap<String, Set<String>>();
            for (String type : nodes.keys()) {
                if (!nodeTypes.contains(type))
                    throw new IllegalArgumentException("members.nodes names unadvertised type " + type);
                nodeMembers.put(type, identifierSet(nodes, target, type, false));
            }
            for (String type : nodeTypes) {
                Set<String> listed = nodeMembers.get(type);
                Vocabulary.Row row = Vocabulary.NODE_SCHEMA.get(type);
                if (listed == null) throw new IllegalArgumentException("members.nodes omits advertised type " + type);
                if (row != null && !listed.containsAll(row.required))
                    throw new IllegalArgumentException("members.nodes." + type + " omits a required member");
            }
            if (universal.contains("semantics") && semantics.isEmpty())
                throw new IllegalArgumentException("semantics advertised universally with an empty semantics list");
            members = new Members(universal, Collections.unmodifiableMap(nodeMembers), semantics, surface);
        }
        return new Profile(target, nodeTypes, builtins, features, extensions, members);
    }

    private static Set<String> identifierSet(Json.Obj o, String target, String member, boolean namespaced) {
        Json.Arr arr = o.array(member);
        if (arr == null) throw new IllegalArgumentException("surface_profiles." + target + "." + member + " must be an array");
        Set<String> out = new HashSet<String>();
        for (int i = 0; i < arr.size(); i++) {
            String s = arr.get(i).stringOrNull();
            if (s == null || !Envelope.isBoundedIdentifier(s) || (namespaced && s.indexOf('.') < 0))
                throw new IllegalArgumentException("surface_profiles." + target + "." + member + " has an invalid entry");
            if (!out.add(s))
                throw new IllegalArgumentException("surface_profiles." + target + "." + member + " has a duplicate");
        }
        return Collections.unmodifiableSet(out);
    }
}
