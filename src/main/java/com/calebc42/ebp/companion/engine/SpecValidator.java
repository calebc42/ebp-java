// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 13.4/16/17 document validation for an `app:*` SurfaceSpec against
// one advertised target profile.  Ported in scope from ebp-kmp's
// SpecValidator: the node walk, identity, depth and count limits, universal
// attribute typing, strict `members` admission, per-type schemas for the
// types this Companion implements (text, column, button, canvas), degrade
// rules for unknown or unadvertised types, and the SPEC 14.1/14.2 action
// descriptor rules.  Stateful node types are not implemented by this
// Companion and are never advertised, so no draft registration exists.
package com.calebc42.ebp.companion.engine;

import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.rpc.Envelope;
import com.calebc42.ebp.companion.wire.WireLimits;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpecValidator {

    /** Limits and advertisement context for one validation. */
    public static final class Context {
        public final Profile profile;
        public final long maxCaptureFields;
        public final long maxCanvasOps;
        public final boolean offlineWakeGranted;
        public Context(Profile profile, long maxCaptureFields, long maxCanvasOps, boolean offlineWakeGranted) {
            this.profile = profile; this.maxCaptureFields = maxCaptureFields;
            this.maxCanvasOps = maxCanvasOps; this.offlineWakeGranted = offlineWakeGranted;
        }
    }

    /** What the walk learned: every authored node id, and whether the
     * document is multi-view. */
    public static final class Result {
        public final boolean multiView;
        public final Set<String> ids;
        Result(boolean multiView, Set<String> ids) { this.multiView = multiView; this.ids = ids; }
    }

    // SPEC 17.5: the closed canvas-op shapes — required members per known op.
    // An UNKNOWN op is skipped at render, never rejected (SPEC 17.5).
    static final Map<String, List<String>> CANVAS_OPS;
    static {
        Map<String, List<String>> m = new HashMap<String, List<String>>();
        m.put("line", Arrays.asList("x1", "y1", "x2", "y2"));
        m.put("rect", Arrays.asList("x", "y", "width", "height"));
        m.put("circle", Arrays.asList("cx", "cy", "radius"));
        m.put("path", Arrays.asList("points"));
        m.put("text", Arrays.asList("x", "y", "text"));
        CANVAS_OPS = Collections.unmodifiableMap(m);
    }

    private static final Set<String> CONFIRM_MEMBERS = new HashSet<String>(
        Arrays.asList("text", "title", "icon", "confirm_label", "dismiss_label"));

    /** Contract-defined application-data members: objects below these names
     * are values, not node positions. */
    private static final Set<String> OPAQUE_WALK_MEMBERS = new HashSet<String>(
        Arrays.asList("args", "meta", "value"));

    private final Context ctx;
    private final Set<String> ids = new HashSet<String>();
    private int nodeCount = 0;
    private int nodeDepth = 0;

    private SpecValidator(Context ctx) { this.ctx = ctx; }

    /** Validate SPEC as an `app:*` SurfaceSpec at PATH. */
    public static Result validateAppSpec(Json.Value spec, String path, Context ctx) {
        if (!(spec instanceof Json.Obj)) throw new ContentInvalid(path, "spec must be an object");
        Json.Obj o = (Json.Obj) spec;
        SpecValidator v = new SpecValidator(ctx);
        boolean multiView;
        if (o.has("views")) {
            // SPEC 13.4: the multi-view object is closed to {views, initial_view}.
            for (String key : o.keys())
                if (!key.equals("views") && !key.equals("initial_view"))
                    throw new ContentInvalid(path + "." + key, "unknown multi-view member");
            Profile.Members m = ctx.profile.members;
            if (m != null && (!m.surface.contains("views") || !m.surface.contains("initial_view")))
                throw new ContentInvalid(path + ".views", "multi-view members are not advertised by the target profile");
            Json.Obj views = o.object("views");
            if (views == null) throw new ContentInvalid(path + ".views", "views must be an object");
            if (views.size() == 0) throw new ContentInvalid(path + ".views", "views must be non-empty");
            String initial = o.string("initial_view");
            if (initial == null || !views.has(initial))
                throw new ContentInvalid(path + ".initial_view", "must name an existing view");
            for (String name : views.keys()) {
                if (!Envelope.isBoundedIdentifier(name))
                    throw new ContentInvalid(path + ".views." + name, "view names are identifiers");
                Json.Value view = views.get(name);
                if (!(view instanceof Json.Obj) || ((Json.Obj) view).string("t") == null)
                    throw new ContentInvalid(path + ".views." + name, "each view must be a node");
                v.walkNode((Json.Obj) view, path + ".views." + name, null, null);
            }
            multiView = true;
        } else {
            // SPEC 16.1: a single-view surface root is itself a node.
            if (o.string("t") == null)
                throw new ContentInvalid(path, "surface root must be a node or a multi-view object");
            v.walkNode(o, path, null, null);
            multiView = false;
        }
        return new Result(multiView, Collections.unmodifiableSet(v.ids));
    }

    /** SPEC 13.5: a stale_spec uses the same variant as the primary spec and
     * MUST NOT contain a stateful node (none are advertised here). */
    public static void validateStaleSpec(Json.Value staleSpec, boolean primaryIsMultiView, String path, Context ctx) {
        Result r = validateAppSpec(staleSpec, path, ctx);
        if (r.multiView != primaryIsMultiView)
            throw new ContentInvalid(path, "stale_spec must use the same variant as spec");
    }

    // ------------------------------------------------------------ walking

    private void walkValue(Json.Value value, String path, Map<String, String> siblingKeys, String parentType) {
        if (value instanceof Json.Arr) {
            Json.Arr a = (Json.Arr) value;
            for (int i = 0; i < a.size(); i++)
                walkValue(a.get(i), path + "[" + i + "]", siblingKeys, parentType);
        } else if (value instanceof Json.Obj) {
            Json.Obj o = (Json.Obj) value;
            if (o.has("t")) {
                walkNode(o, path, siblingKeys, parentType);
            } else {
                for (Map.Entry<String, Json.Value> e : o.entries()) {
                    String key = e.getKey();
                    Json.Value child = e.getValue();
                    if (OPAQUE_WALK_MEMBERS.contains(key)) continue;
                    if (Vocabulary.ACTION_HOOK_KEYS.contains(key) && child instanceof Json.Obj)
                        validateAction((Json.Obj) child, path + "." + key, key);
                    else walkValue(child, path + "." + key, siblingKeys, parentType);
                }
            }
        }
        // scalars carry no schema
    }

    private void walkNode(Json.Obj node, String path, Map<String, String> siblingKeys, String parentType) {
        // SPEC 4.5: at most 10,000 nodes in one surface snapshot; count before
        // descending so an over-limit tree rejects, not renders.
        if (++nodeCount > WireLimits.MAX_NODES_PER_SNAPSHOT)
            throw new ContentInvalid(path, "exceeds max_nodes_per_snapshot");
        // SPEC 4.5/16.1 (amendment #108): at most 20 levels deep.
        if (++nodeDepth > WireLimits.MAX_NODE_DEPTH) {
            nodeDepth--;
            throw new ContentInvalid(path, "node-depth");
        }
        try {
            validateSemantics(node, path);
            validateNode(node, path);
            String key = node.string("key");
            if (key != null && siblingKeys != null) {
                String first = siblingKeys.get(key);
                if (first != null)
                    throw new ContentInvalid(path + ".key", "duplicate sibling key (first at " + first + ")");
                siblingKeys.put(key, path);
            }
            String t = node.stringOr("t", "");
            // SPEC 16.2/17.1: a known type absent from the target profile
            // degrades exactly like an unknown type — its subtree is scanned,
            // but its per-type hooks/schema are not applied and it dispatches
            // nothing.
            boolean known = Vocabulary.NODE_SCHEMA.containsKey(t) && ctx.profile.nodeTypes.contains(t);
            Map<String, String> childSiblingKeys = new HashMap<String, String>();
            for (Map.Entry<String, Json.Value> e : node.entries()) {
                String member = e.getKey();
                Json.Value child = e.getValue();
                if (OPAQUE_WALK_MEMBERS.contains(member) || member.equals("semantics")) continue;
                if (known && Vocabulary.ACTION_HOOK_KEYS.contains(member)) {
                    // SPEC 17.1: an `on_*` member is an ActionDescriptor.
                    if (!(child instanceof Json.Obj))
                        throw new ContentInvalid(path + "." + member, "action descriptor must be an object");
                    validateAction((Json.Obj) child, path + "." + member, member);
                } else if (known && member.equals("children")) {
                    // SPEC 17.1: `children` is an array of Nodes; SPEC 4.5: at
                    // most 10,000 children of one node.
                    if (!(child instanceof Json.Arr))
                        throw new ContentInvalid(path + ".children", "children must be an array of nodes");
                    Json.Arr children = (Json.Arr) child;
                    if (children.size() > WireLimits.MAX_CHILDREN_PER_NODE)
                        throw new ContentInvalid(path + ".children", "exceeds max_children_per_node");
                    for (int index = 0; index < children.size(); index++) {
                        Json.Value element = children.get(index);
                        if (!(element instanceof Json.Obj))
                            throw new ContentInvalid(path + ".children[" + index + "]", "child must be a node object");
                        if (((Json.Obj) element).string("t") == null)
                            throw new ContentInvalid(path + ".children[" + index + "]", "child node missing discriminator t");
                        walkNode((Json.Obj) element, path + ".children[" + index + "]", childSiblingKeys, t);
                    }
                } else {
                    // An unsupported node degrades to its descendants; it
                    // cannot grant renderer-owned parent behavior.
                    walkValue(child, path + "." + member, childSiblingKeys, known ? t : null);
                }
            }
        } finally {
            nodeDepth--;
        }
    }

    // ---------------------------------------------------------- semantics

    /** SPEC 16.5.1: the Semantics envelope is validated before type/profile
     * degrade; unknown members are ignored for compatible growth, every
     * recognized member and nested descriptor is fully gated. */
    private void validateSemantics(Json.Obj node, String path) {
        if (!node.has("semantics")) return;
        String p = path + ".semantics";
        Json.Value v = node.get("semantics");
        if (!(v instanceof Json.Obj)) throw new ContentInvalid(p, "semantics must be an object");
        Json.Obj s = (Json.Obj) v;
        Profile.Members m = ctx.profile.members;
        if (m != null && !m.universal.contains("semantics"))
            throw new ContentInvalid(p, "universal member is not advertised by the target profile");
        for (String member : s.keys()) {
            if (!Vocabulary.SEMANTICS_MEMBERS.contains(member)) continue; // compatible growth
            if (m != null && !m.semantics.contains(member))
                throw new ContentInvalid(p + "." + member, "semantics member is not advertised by the target profile");
            Json.Value value = s.get(member);
            if (member.equals("name") || member.equals("description") || member.equals("state_description")
                || member.equals("error") || member.equals("pane_title")) {
                String str = value.stringOrNull();
                if (str == null || str.isEmpty())
                    throw new ContentInvalid(p + "." + member, "must be a non-empty string");
            } else if (member.equals("heading_level")) {
                Long n = value.integralOrNull();
                if (n == null || n.longValue() < 1 || n.longValue() > 6)
                    throw new ContentInvalid(p + ".heading_level", "must be an integer 1..6");
            } else if (member.equals("live_region")) {
                String str = value.stringOrNull();
                if (str == null || !(str.equals("polite") || str.equals("assertive")))
                    throw new ContentInvalid(p + ".live_region", "must be polite or assertive");
            } else if (member.equals("traversal_group")) {
                if (value.booleanOrNull() == null)
                    throw new ContentInvalid(p + ".traversal_group", "must be a boolean");
            } else if (member.equals("traversal_index")) {
                Double d = value.doubleOrNull();
                if (d == null || d.isNaN() || d.isInfinite())
                    throw new ContentInvalid(p + ".traversal_index", "must be a finite number");
            } else if (member.equals("collection")) {
                if (!(value instanceof Json.Obj)) throw new ContentInvalid(p + ".collection", "must be an object");
                Json.Obj c = (Json.Obj) value;
                Long rows = c.integral("row_count"), cols = c.integral("column_count");
                if (rows == null || rows.longValue() < 0 || cols == null || cols.longValue() < 1 || c.size() != 2)
                    throw new ContentInvalid(p + ".collection", "must be {row_count, column_count}");
            } else if (member.equals("collection_item")) {
                if (!(value instanceof Json.Obj)) throw new ContentInvalid(p + ".collection_item", "must be an object");
                Json.Obj c = (Json.Obj) value;
                Long ri = c.integral("row_index"), rs = c.integral("row_span");
                Long ci = c.integral("column_index"), cs = c.integral("column_span");
                if (ri == null || ri.longValue() < 0 || rs == null || rs.longValue() < 1
                    || ci == null || ci.longValue() < 0 || cs == null || cs.longValue() < 1 || c.size() != 4)
                    throw new ContentInvalid(p + ".collection_item", "must be {row_index, row_span, column_index, column_span}");
            } else if (member.equals("actions")) {
                if (!(value instanceof Json.Arr)) throw new ContentInvalid(p + ".actions", "must be an array");
                Json.Arr actions = (Json.Arr) value;
                if (actions.size() > WireLimits.MAX_SEMANTIC_ACTIONS_PER_NODE)
                    throw new ContentInvalid(p + ".actions", "exceeds max_semantic_actions_per_node");
                Set<String> labels = new HashSet<String>();
                for (int i = 0; i < actions.size(); i++) {
                    Json.Value a = actions.get(i);
                    String ap = p + ".actions[" + i + "]";
                    if (!(a instanceof Json.Obj)) throw new ContentInvalid(ap, "must be {label, on_action}");
                    Json.Obj ao = (Json.Obj) a;
                    String label = ao.string("label");
                    if (label == null || label.isEmpty() || !labels.add(label))
                        throw new ContentInvalid(ap + ".label", "must be a distinct non-empty string");
                    Json.Value on = ao.get("on_action");
                    if (!(on instanceof Json.Obj)) throw new ContentInvalid(ap + ".on_action", "must be a descriptor");
                    if (ao.size() != 2) throw new ContentInvalid(ap, "must be exactly {label, on_action}");
                    validateAction((Json.Obj) on, ap + ".on_action", "on_action");
                }
            }
        }
    }

    // --------------------------------------------------------------- nodes

    private void validateNode(Json.Obj node, String path) {
        String t = node.string("t");
        if (t == null) throw new ContentInvalid(path, "node discriminator t must be a string");
        // SPEC 16.1/16.3: universal identity attributes keep their declared
        // identifier type even on an unsupported node.
        identityMember(node, path, "key");
        String id = identityMember(node, path, "id");
        if (id != null && !ids.add(id)) throw new ContentInvalid(path + ".id", "duplicate node ID");
        // SPEC 17.1/16.2: a type absent from the target profile is unsupported —
        // degrade like an unknown type.
        if (!ctx.profile.nodeTypes.contains(t)) return;
        Vocabulary.Row row = Vocabulary.NODE_SCHEMA.get(t);
        if (row == null) return; // SPEC 16.2: unknown types degrade
        Profile.Members support = ctx.profile.members;
        if (support != null) {
            Set<String> nodeMembers = support.nodes.get(t);
            if (nodeMembers == null) throw new ContentInvalid(path, "target member map omits node type " + t);
            for (String member : node.keys()) {
                if (member.equals("t")) continue;
                // A member the node's own schema lists (canvas `width`, say)
                // is node-specific there even though SPEC 16.5 also names it
                // universally; the node list governs it.
                if (row.lists(member)) {
                    if (!nodeMembers.contains(member))
                        throw new ContentInvalid(path + "." + member, "node member is not advertised by the target profile");
                } else if (Vocabulary.UNIVERSAL_NODE_ATTRIBUTES.contains(member)) {
                    if (!support.universal.contains(member))
                        throw new ContentInvalid(path + "." + member, "universal member is not advertised by the target profile");
                }
            }
        }
        for (String req : row.required)
            if (!node.has(req)) throw new ContentInvalid(path, t + " missing required " + req);
        for (String member : row.required) checkScalarFieldType(member, node, path);
        for (String member : row.optional) if (node.has(member)) checkScalarFieldType(member, node, path);
        validateUniversal(node, path);
        if (t.equals("text")) validateText(node, path);
        else if (t.equals("column")) validateColumn(node, path);
        else if (t.equals("button")) validateButton(node, path);
        else if (t.equals("canvas")) validateCanvas(node, path);
        // Any other advertised type would need its own rules; this Companion
        // advertises exactly the four above (Companion.checkProfile).
    }

    private static String identityMember(Json.Obj node, String path, String name) {
        if (!node.has(name)) return null;
        String value = node.string(name);
        if (value == null) throw new ContentInvalid(path + "." + name, "must be an identifier");
        if (!Envelope.isBoundedIdentifier(value)) throw new ContentInvalid(path + "." + name, "invalid identifier");
        return value;
    }

    /** SPEC 16.5: universal attribute typing (finite non-negative dp values,
     * bounded fractions, booleans, colors, and the closed Pad/Corner/Border
     * objects).  A present wrong-typed value is malformed, never omission. */
    private static void validateUniversal(Json.Obj node, String path) {
        for (String m : new String[] {"padding", "width", "height", "min_width", "max_width", "min_height", "max_height"})
            if (node.has(m)) nonNegativeNumber(node, m, path);
        if (node.has("fill_fraction")) unitInterval(node, "fill_fraction", path);
        if (node.has("alpha")) unitInterval(node, "alpha", path);
        if (node.has("aspect_ratio")) positiveNumber(node, "aspect_ratio", path);
        if (node.has("weight")) positiveNumber(node, "weight", path);
        if (node.has("scroll_here") && node.bool("scroll_here") == null)
            throw new ContentInvalid(path + ".scroll_here", "must be a boolean");
        if (node.has("clip") && node.bool("clip") == null)
            throw new ContentInvalid(path + ".clip", "must be a boolean");
        if (node.has("bg")) color(node, "bg", path);
        if (node.has("align_self")) {
            String v = node.string("align_self");
            if (v == null || !Vocabulary.ENUMS.get("align_self").contains(v))
                throw new ContentInvalid(path + ".align_self", "unknown alignment");
        }
        if (node.has("pad")) {
            Json.Obj pad = node.object("pad");
            if (pad == null) throw new ContentInvalid(path + ".pad", "must be an object");
            for (String side : pad.keys()) {
                if (!(side.equals("start") || side.equals("top") || side.equals("end") || side.equals("bottom")
                      || side.equals("horizontal") || side.equals("vertical")))
                    throw new ContentInvalid(path + ".pad." + side, "unknown pad member");
                nonNegativeNumber(pad, side, path + ".pad");
            }
        }
        if (node.has("corner")) {
            Json.Value c = node.get("corner");
            if (c instanceof Json.Obj) {
                Json.Obj corner = (Json.Obj) c;
                for (String k : corner.keys()) {
                    if (!(k.equals("top_start") || k.equals("top_end") || k.equals("bottom_start") || k.equals("bottom_end")))
                        throw new ContentInvalid(path + ".corner." + k, "unknown corner member");
                    nonNegativeNumber(corner, k, path + ".corner");
                }
            } else {
                nonNegativeNumber(node, "corner", path);
            }
        }
        if (node.has("border")) {
            Json.Obj border = node.object("border");
            if (border == null) throw new ContentInvalid(path + ".border", "must be {width, color}");
            for (String k : border.keys())
                if (!(k.equals("width") || k.equals("color")))
                    throw new ContentInvalid(path + ".border." + k, "unknown border member");
            if (border.has("width")) nonNegativeNumber(border, "width", path + ".border");
            if (border.has("color")) color(border, "color", path + ".border");
        }
    }

    private static void nonNegativeNumber(Json.Obj o, String m, String path) {
        Double d = o.number(m);
        if (d == null || d.isNaN() || d.isInfinite() || d.doubleValue() < 0)
            throw new ContentInvalid(path + "." + m, "must be a finite non-negative number");
    }

    private static void positiveNumber(Json.Obj o, String m, String path) {
        Double d = o.number(m);
        if (d == null || d.isNaN() || d.isInfinite() || d.doubleValue() <= 0)
            throw new ContentInvalid(path + "." + m, "must be a finite positive number");
    }

    private static void unitInterval(Json.Obj o, String m, String path) {
        Double d = o.number(m);
        if (d == null || d.isNaN() || d.doubleValue() < 0 || d.doubleValue() > 1)
            throw new ContentInvalid(path + "." + m, "must be a number 0..1");
    }

    /** SPEC 16.6: a Color is a theme-role identifier or #rgb/#rgba/#rrggbb/#rrggbbaa. */
    static boolean isColor(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.charAt(0) != '#') return Envelope.isBoundedIdentifier(s);
        int n = s.length() - 1;
        if (!(n == 3 || n == 4 || n == 6 || n == 8)) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    private static void color(Json.Obj o, String m, String path) {
        if (!isColor(o.string(m))) throw new ContentInvalid(path + "." + m, "must be a Color");
    }

    /** Type-check one type-specific member against the contract's
     * FIELD_TYPES for the coercion-prone scalar categories. */
    private static void checkScalarFieldType(String member, Json.Obj node, String path) {
        String type = Vocabulary.FIELD_TYPES.get(member);
        if (type == null) return;
        String p = path + "." + member;
        Json.Value v = node.get(member);
        if (type.equals("boolean")) {
            if (v.booleanOrNull() == null) throw new ContentInvalid(p, member + " must be a boolean");
        } else if (type.equals("string") || type.equals("yyyy-mm")) {
            if (v.stringOrNull() == null) throw new ContentInvalid(p, member + " must be a string");
        } else if (type.equals("identifier")) {
            String s = v.stringOrNull();
            if (s == null || !Envelope.isBoundedIdentifier(s)) throw new ContentInvalid(p, member + " must be an identifier");
        } else if (type.equals("color")) {
            if (!isColor(v.stringOrNull())) throw new ContentInvalid(p, member + " must be a color string");
        } else if (type.equals("string-or-number")) {
            if (v.stringOrNull() == null && v.doubleOrNull() == null)
                throw new ContentInvalid(p, member + " must be a string or number");
        } else if (type.equals("dp") || type.equals("number")) {
            Double d = v.doubleOrNull();
            if (d == null || d.isInfinite() || d.isNaN()) throw new ContentInvalid(p, member + " must be a finite number");
            if (type.equals("dp") && d.doubleValue() < 0) throw new ContentInvalid(p, member + " must be non-negative");
        } else if (type.equals("non-negative-integer")) {
            Long n = v.integralOrNull();
            if (n == null || n.longValue() < 0) throw new ContentInvalid(p, member + " must be a non-negative integer");
        } else if (type.equals("positive-integer")) {
            Long n = v.integralOrNull();
            if (n == null || n.longValue() < 1) throw new ContentInvalid(p, member + " must be a positive integer");
        }
        // enum, font-weight, varies-per-node, and complex types have
        // dedicated rules in the per-type validators.
    }

    private static void validateText(Json.Obj node, String path) {
        // SPEC 17.2: `style` falls back to body for an unknown value (SPEC
        // 12 rule 6) but MUST still be a string when present.
        if (node.has("style") && node.string("style") == null)
            throw new ContentInvalid(path + ".style", "must be a string");
        if (node.has("font_weight")) {
            Json.Value fw = node.get("font_weight");
            String s = fw.stringOrNull();
            Long n = fw.integralOrNull();
            boolean ok = (s != null && (s.equals("normal") || s.equals("bold")))
                || (n != null && n.longValue() >= 100 && n.longValue() <= 900 && n.longValue() % 100 == 0);
            if (!ok) throw new ContentInvalid(path + ".font_weight", "must be normal, bold, or a multiple of 100 in 100..900");
        }
        if (node.has("syntax") && !Envelope.isBoundedIdentifier(node.string("syntax")))
            throw new ContentInvalid(path + ".syntax", "must be an identifier");
    }

    private static void validateColumn(Json.Obj node, String path) {
        if (node.has("align")) {
            String v = node.string("align");
            if (v == null || !Vocabulary.ENUMS.get("column.align").contains(v))
                throw new ContentInvalid(path + ".align", "unknown alignment");
        }
        if (node.has("arrange")) {
            String v = node.string("arrange");
            if (v == null || !Vocabulary.ENUMS.get("arrange").contains(v))
                throw new ContentInvalid(path + ".arrange", "unknown arrangement");
        }
        if (node.has("fill") && node.bool("fill") == null)
            throw new ContentInvalid(path + ".fill", "must be a boolean");
    }

    private static void validateButton(Json.Obj node, String path) {
        if (node.has("variant")) {
            String v = node.string("variant");
            if (v == null || !Vocabulary.ENUMS.get("button.variant").contains(v))
                throw new ContentInvalid(path + ".variant", "unknown button variant");
        }
        if (node.has("shape")) {
            String v = node.string("shape");
            if (v == null || !Vocabulary.ENUMS.get("surface.shape").contains(v))
                throw new ContentInvalid(path + ".shape", "unknown shape");
        }
        if (node.has("size")) {
            Double d = node.number("size");
            String s = node.string("size");
            if ((d == null || d.isNaN() || d.isInfinite() || d.doubleValue() < 0) && s == null)
                throw new ContentInvalid(path + ".size", "must be a size name or a non-negative number");
        }
        if (node.has("expanded")) {
            Json.Value e = node.get("expanded");
            if (e.booleanOrNull() == null && !"auto".equals(e.stringOrNull()))
                throw new ContentInvalid(path + ".expanded", "must be a boolean or \"auto\"");
        }
        // SPEC 14.6: `checked` makes the button stateful; this Companion does
        // not implement stateful nodes and its profile does not advertise the
        // member, so `members` admission rejects it before this point when
        // advertised; without members it is content-invalid here.
        if (node.has("checked"))
            throw new ContentInvalid(path + ".checked", "stateful buttons are not supported by this target");
    }

    private void validateCanvas(Json.Obj node, String path) {
        for (String dim : new String[] {"width", "height"}) {
            Double v = node.number(dim);
            if (v == null || v.isNaN() || v.isInfinite() || v.doubleValue() <= 0)
                throw new ContentInvalid(path + "." + dim, "must be positive");
        }
        Json.Arr ops = node.array("ops");
        if (ops == null) throw new ContentInvalid(path + ".ops", "ops must be an array");
        // SPEC 4.5: max_canvas_ops bounds the op count (unknown ops still count).
        if (ops.size() > ctx.maxCanvasOps) throw new ContentInvalid(path + ".ops", "exceeds max_canvas_ops");
        for (int i = 0; i < ops.size(); i++) {
            Json.Value ov = ops.get(i);
            String op = path + ".ops[" + i + "]";
            if (!(ov instanceof Json.Obj)) throw new ContentInvalid(op, "must be an op object");
            Json.Obj o = (Json.Obj) ov;
            String kind = o.string("op");
            if (kind == null) throw new ContentInvalid(op + ".op", "op discriminator required");
            List<String> required = CANVAS_OPS.get(kind);
            if (required == null) continue; // unknown op: render-skip
            for (String m : required) {
                if (!o.has(m)) throw new ContentInvalid(op, kind + " missing required " + m);
                if (!m.equals("points") && !m.equals("text")) finite(o, m, op);
            }
            if (kind.equals("text") && o.string("text") == null)
                throw new ContentInvalid(op + ".text", "must be a string");
            if (kind.equals("path")) {
                Json.Arr pts = o.array("points");
                if (pts == null) throw new ContentInvalid(op + ".points", "must be an array");
                for (int j = 0; j < pts.size(); j++) {
                    Json.Value pv = pts.get(j);
                    String pp = op + ".points[" + j + "]";
                    if (!(pv instanceof Json.Obj) || ((Json.Obj) pv).size() != 2)
                        throw new ContentInvalid(pp, "must be {x, y}");
                    finite((Json.Obj) pv, "x", pp);
                    finite((Json.Obj) pv, "y", pp);
                }
                if (o.has("closed") && o.bool("closed") == null)
                    throw new ContentInvalid(op + ".closed", "must be a boolean");
            }
            // SPEC 17.5: rect widths/heights, circle radii, stroke widths, and
            // a line op's width are non-negative.
            String[] nonNegative = kind.equals("rect") ? new String[] {"width", "height", "stroke_width"}
                : kind.equals("circle") ? new String[] {"radius", "stroke_width"}
                : kind.equals("line") ? new String[] {"width"}
                : new String[] {"stroke_width"};
            for (String m : nonNegative) {
                if (o.has(m)) {
                    Double v = o.number(m);
                    if (v == null || v.isNaN() || v.doubleValue() < 0)
                        throw new ContentInvalid(op + "." + m, "must be non-negative");
                }
            }
            if (kind.equals("text") && o.has("size")) {
                Double v = o.number("size");
                if (v == null || v.isNaN() || v.doubleValue() < 0)
                    throw new ContentInvalid(op + ".size", "must be non-negative");
            }
            if (o.has("color") && !isColor(o.string("color")))
                throw new ContentInvalid(op + ".color", "must be a Color");
            if (o.has("fill") && !isColor(o.string("fill")))
                throw new ContentInvalid(op + ".fill", "must be a Color");
        }
    }

    private static void finite(Json.Obj o, String m, String path) {
        Double v = o.number(m);
        if (v == null || v.isNaN() || v.isInfinite())
            throw new ContentInvalid(path + "." + m, "must be a finite number");
    }

    // ------------------------------------------------------------- actions

    private void validateAction(Json.Obj obj, String path, String hook) {
        boolean hasAction = obj.has("action");
        boolean hasBuiltin = obj.has("builtin");
        if (hasAction == hasBuiltin) throw new ContentInvalid(path, "exactly one of action/builtin");
        Vocabulary.Row row;
        if (hasAction) {
            row = Vocabulary.ACTION_SCHEMA.get("remote");
            String name = obj.string("action");
            if (name == null) throw new ContentInvalid(path + ".action", "must be a string");
            if (name.indexOf('.') < 0 || !Envelope.isBoundedIdentifier(name))
                throw new ContentInvalid(path + ".action", "action names are dotted identifiers");
            // `opt(k) ?: DEFAULT`: only an ABSENT member takes the default.
            String policy = obj.has("when_offline") ? obj.string("when_offline") : Vocabulary.OFFLINE_DEFAULT;
            if (policy == null || !Vocabulary.OFFLINE_POLICIES.contains(policy))
                throw new ContentInvalid(path + ".when_offline", "unknown offline policy");
            if (policy.equals("wake") && !ctx.offlineWakeGranted)
                throw new ContentInvalid(path + ".when_offline", "wake requires the offline.wake grant");
            if ((policy.equals("queue") || policy.equals("wake")) && !obj.has("ttl_s"))
                throw new ContentInvalid(path, policy + " requires ttl_s");
            if (policy.equals("drop") && (obj.has("ttl_s") || obj.has("dedupe")))
                throw new ContentInvalid(path, "ttl_s/dedupe are invalid for drop");
            // SPEC 14.1: ttl_s is an integer 1..604800, read by value.
            if (obj.has("ttl_s")) {
                Long ttl = obj.get("ttl_s").integralOrNull();
                if (ttl == null || ttl.longValue() < 1 || ttl.longValue() > 604800)
                    throw new ContentInvalid(path + ".ttl_s", "must be an integer 1..604800");
            }
            if (obj.has("dedupe") && !Envelope.isBoundedIdentifier(obj.string("dedupe")))
                throw new ContentInvalid(path + ".dedupe", "must be an identifier");
            if (obj.has("args") && !(obj.get("args") instanceof Json.Obj))
                throw new ContentInvalid(path + ".args", "must be an object");
            if (obj.has("confirm")) validateConfirm(obj.get("confirm"), path + ".confirm");
            if (obj.has("open_surface")) {
                String target = obj.string("open_surface");
                if (target == null || !SurfaceStore.isAppSurfaceId(target))
                    throw new ContentInvalid(path + ".open_surface", "must be an app Surface ID");
                if (!ctx.profile.features.contains("action.open_surface"))
                    throw new ContentInvalid(path + ".open_surface", "action.open_surface not valid in this context");
            }
            // SPEC 14.3: a remote descriptor on a value-producing hook MUST
            // NOT author the member the Companion injects.
            Set<String> injected = Vocabulary.INJECTIONS.get(hook);
            Json.Obj args = obj.object("args");
            if (injected != null && args != null)
                for (String m : injected)
                    if (args.has(m)) throw new ContentInvalid(path + ".args." + m, "conflicts with the value injected by " + hook);
        } else {
            String name = obj.string("builtin");
            if (name == null) throw new ContentInvalid(path + ".builtin", "must be a string");
            // SPEC 14.2: an unknown builtin rejects the containing document.
            row = Vocabulary.ACTION_SCHEMA.get(name);
            if (row == null) throw new ContentInvalid(path + ".builtin", "unknown builtin " + name);
            // SPEC 14.2: a builtin not advertised for THIS target is an
            // invalid context and rejects the document.
            if (!ctx.profile.builtins.contains(name))
                throw new ContentInvalid(path + ".builtin", "builtin " + name + " not valid in this context");
            if (name.equals("view.switch")) {
                String view = obj.string("view");
                if (view == null || !Envelope.isBoundedIdentifier(view))
                    throw new ContentInvalid(path + ".view", "must be a view identifier");
            } else if (name.equals("surface.open")) {
                String target = obj.string("surface");
                if (target == null || !SurfaceStore.isAppSurfaceId(target))
                    throw new ContentInvalid(path + ".surface", "must be an app Surface ID");
            } else if (name.equals("variant.switch") || name.equals("trigger.fire")) {
                String id = obj.string("id");
                if (id == null || !Envelope.isBoundedIdentifier(id))
                    throw new ContentInvalid(path + ".id", "must be an identifier");
            } else if (name.equals("uri.open")) {
                String uri = obj.string("uri");
                if (uri == null || !validUriOpenTarget(uri))
                    throw new ContentInvalid(path + ".uri", "must be a URI in enums.uri.scheme");
            } else if (name.equals("clipboard.copy") || name.equals("share.send")) {
                if (obj.string("text") == null) throw new ContentInvalid(path + ".text", "must be a string");
                if (obj.has("title") && obj.string("title") == null)
                    throw new ContentInvalid(path + ".title", "must be a string");
            } else if (name.equals("dialog.submit") || name.equals("dialog.dismiss")) {
                // SPEC 14.2: valid only inside an outstanding dialog; this
                // Companion never grants surfaces.dialog, so the builtins are
                // never advertised and the branch above already rejected.
                throw new ContentInvalid(path + ".builtin", "dialog builtins are valid only inside a dialog");
            }
        }
        // SPEC 14.1: capture_fields is an array of distinct widget IDs no
        // longer than max_capture_fields resolving to stateful nodes; no
        // stateful node type is advertised here, so any capture is invalid.
        if (obj.has("capture_fields")) {
            Json.Arr cf = obj.array("capture_fields");
            if (cf == null) throw new ContentInvalid(path + ".capture_fields", "must be an array");
            if (cf.size() > ctx.maxCaptureFields)
                throw new ContentInvalid(path + ".capture_fields", "exceeds max_capture_fields");
            if (cf.size() > 0)
                throw new ContentInvalid(path + ".capture_fields[0]", "must name a stateful node in the document");
        }
        for (String req : row.required)
            if (!req.equals("builtin") && !obj.has(req)) throw new ContentInvalid(path, "action missing required " + req);
        for (String key : obj.keys())
            if (!row.required.contains(key) && !row.optional.contains(key) && !key.equals("builtin") && !key.equals("action"))
                throw new ContentInvalid(path + "." + key, "unknown action member");
    }

    /** SPEC 14.1 (amendment #168): `confirm` is a non-empty string or the
     * authored face {text, title?, icon?, confirm_label?, dismiss_label?}. */
    private static void validateConfirm(Json.Value c, String path) {
        if (!(c instanceof Json.Obj)) {
            String s = c == null ? null : c.stringOrNull();
            if (s == null || s.isEmpty()) throw new ContentInvalid(path, "must be a non-empty string or a confirm object");
            return;
        }
        Json.Obj face = (Json.Obj) c;
        for (String k : face.keys())
            if (!CONFIRM_MEMBERS.contains(k)) throw new ContentInvalid(path + "." + k, "unknown confirm member");
        String text = face.string("text");
        if (text == null || text.isEmpty()) throw new ContentInvalid(path + ".text", "must be a non-empty string");
        for (String m : new String[] {"title", "confirm_label", "dismiss_label"})
            if (face.has(m) && face.string(m) == null) throw new ContentInvalid(path + "." + m, "must be a string");
        if (face.has("icon") && !Envelope.isBoundedIdentifier(face.string("icon")))
            throw new ContentInvalid(path + ".icon", "must be an identifier");
    }

    /** Amendment #208: a uri.open target — an absolute URI of at most 2048
     *  octets in the closed uri.scheme set, with no user information. */
    static boolean validUriOpenTarget(String uri) {
        if (uri.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2048) return false;
        int colon = uri.indexOf(':');
        if (colon <= 0) return false;
        String scheme = uri.substring(0, colon);
        if (!Character.isLetter(scheme.charAt(0))) return false;
        for (char ch : scheme.toCharArray())
            if (!(Character.isLetterOrDigit(ch) || ch == '+' || ch == '.' || ch == '-')) return false;
        switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
            case "http": case "https": case "mailto": case "tel": case "voicemail": case "geo":
            case "sms": case "smsto": case "mms": case "mmsto": break;
            default: return false;
        }
        String rest = uri.substring(colon + 1);
        if (rest.startsWith("//")) {
            String authority = rest.substring(2);
            for (char stop : new char[] {'/', '?', '#'}) {
                int at = authority.indexOf(stop);
                if (at >= 0) authority = authority.substring(0, at);
            }
            if (authority.indexOf('@') >= 0) return false;
        }
        return true;
    }
}
