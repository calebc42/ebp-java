// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.calebc42.ebp.companion.engine.ContentInvalid;
import com.calebc42.ebp.companion.engine.Presentation;
import com.calebc42.ebp.companion.engine.SpecValidator;
import com.calebc42.ebp.companion.engine.Vocabulary;
import com.calebc42.ebp.companion.json.Json;
import com.calebc42.ebp.companion.json.JsonParser;
import com.calebc42.ebp.companion.store.SurfaceRecord;

import org.junit.Test;

public class ValidatorTest {

    private final SpecValidator.Context ctx = new SpecValidator.Context(
        Fixtures.config(new Fixtures.CountingRandom()).appProfile, 64, 1024, false);

    private void invalid(String json, String pathFragment) {
        try {
            SpecValidator.validateAppSpec(JsonParser.parse(json), "spec", ctx);
            fail("accepted " + json);
        } catch (ContentInvalid e) {
            assertTrue(e.path + " does not contain " + pathFragment, e.path.contains(pathFragment));
        }
    }

    private SpecValidator.Result valid(String json) {
        return SpecValidator.validateAppSpec(JsonParser.parse(json), "spec", ctx);
    }

    @Test public void milestoneSpecIsValidAndWithinMembers() {
        SpecValidator.Result r = SpecValidator.validateAppSpec(Fixtures.milestoneSpec(true), "spec", ctx);
        assertFalse(r.multiView);
        assertTrue(r.ids.contains("set-on"));
        assertTrue(r.ids.contains("indicator"));
    }

    @Test public void membersAdmissionIsStrict() {
        invalid("{\"t\":\"text\",\"text\":\"x\",\"color\":\"#fff\"}", "color");           // known, unadvertised member
        invalid("{\"t\":\"text\",\"text\":\"x\",\"padding\":4}", "padding");             // universal, unadvertised
        invalid("{\"t\":\"button\",\"label\":\"x\",\"on_tap\":{\"builtin\":\"companion.settings.open\"},\"variant\":\"filled\"}", "variant");
        valid("{\"t\":\"text\",\"text\":\"x\",\"style\":\"caption\",\"id\":\"a\",\"key\":\"k\"}");
        // SPEC 16.3: an unknown (non-contract) member is ignored.
        valid("{\"t\":\"text\",\"text\":\"x\",\"future_member\":1}");
    }

    @Test public void requiredMembersAndTypes() {
        invalid("{\"t\":\"text\"}", "spec");
        invalid("{\"t\":\"text\",\"text\":5}", "text");
        invalid("{\"t\":\"column\",\"children\":{}}", "children");
        invalid("{\"t\":\"column\",\"children\":[{\"text\":\"no t\"}]}", "children[0]");
        invalid("{\"t\":\"button\",\"label\":\"x\"}", "spec");
        invalid("{\"t\":\"button\",\"label\":\"x\",\"on_tap\":\"tap\"}", "on_tap");
        invalid("{\"t\":\"button\",\"label\":\"x\",\"enabled\":\"yes\",\"on_tap\":{\"builtin\":\"companion.settings.open\"}}", "enabled");
        invalid("{\"t\":\"column\",\"spacing\":-1,\"children\":[]}", "spacing");
        invalid("{\"t\":\"text\",\"text\":\"x\",\"id\":\"bad id\"}", "id");
        invalid("{\"t\":\"column\",\"children\":[{\"t\":\"text\",\"text\":\"a\",\"id\":\"x\"},{\"t\":\"text\",\"text\":\"b\",\"id\":\"x\"}]}", "id");
        invalid("{\"t\":\"column\",\"children\":[{\"t\":\"text\",\"text\":\"a\",\"key\":\"x\"},{\"t\":\"text\",\"text\":\"b\",\"key\":\"x\"}]}", "key");
        // SPEC 12 rule 6: an unknown text.style falls back to body, not rejection.
        valid("{\"t\":\"text\",\"text\":\"x\",\"style\":\"gigantic\"}");
    }

    @Test public void unknownAndUnadvertisedTypesDegrade() {
        valid("{\"t\":\"column\",\"children\":[{\"t\":\"row\",\"children\":[{\"t\":\"text\",\"text\":\"x\"}]}]}");
        valid("{\"t\":\"column\",\"children\":[{\"t\":\"hologram\",\"beam\":1}]}");
        // A degraded node still keeps universal identity typing.
        invalid("{\"t\":\"column\",\"children\":[{\"t\":\"row\",\"id\":7}]}", "id");
        // A nested known node inside a degraded one is still validated.
        invalid("{\"t\":\"column\",\"children\":[{\"t\":\"row\",\"children\":[{\"t\":\"text\"}]}]}", "children[0].children[0]");
    }

    @Test public void actionDescriptors() {
        String base = "{\"t\":\"button\",\"label\":\"x\",\"on_tap\":";
        invalid(base + "{}}", "on_tap");
        invalid(base + "{\"action\":\"a.b\",\"builtin\":\"view.switch\"}}", "on_tap");
        invalid(base + "{\"action\":\"nodot\"}}", "action");
        invalid(base + "{\"action\":\"a.b\",\"when_offline\":\"queue\"}}", "on_tap");       // queue needs ttl_s
        invalid(base + "{\"action\":\"a.b\",\"ttl_s\":10}}", "on_tap");                     // drop forbids ttl_s
        invalid(base + "{\"action\":\"a.b\",\"when_offline\":\"queue\",\"ttl_s\":0}}", "ttl_s");
        invalid(base + "{\"action\":\"a.b\",\"when_offline\":\"queue\",\"ttl_s\":604801}}", "ttl_s");
        invalid(base + "{\"action\":\"a.b\",\"when_offline\":\"wake\",\"ttl_s\":10}}", "when_offline"); // no grant
        invalid(base + "{\"action\":\"a.b\",\"confirm\":\"\"}}", "confirm");
        invalid(base + "{\"action\":\"a.b\",\"confirm\":{\"text\":\"ok\",\"colour\":1}}}", "confirm");
        invalid(base + "{\"action\":\"a.b\",\"open_surface\":\"app:x\"}}", "open_surface"); // feature absent
        invalid(base + "{\"action\":\"a.b\",\"capture_fields\":[\"f\"]}}", "capture_fields");
        invalid(base + "{\"action\":\"a.b\",\"extra\":1}}", "extra");
        invalid(base + "{\"builtin\":\"share.send\",\"text\":\"x\"}}", "builtin");           // unadvertised builtin
        invalid(base + "{\"builtin\":\"view.switch\"}}", "on_tap");                          // missing view
        invalid(base + "{\"builtin\":\"companion.settings.open\",\"args\":{}}}", "args");   // remote-only member
        invalid(base + "{\"builtin\":\"dance\"}}", "builtin");
        valid(base + "{\"builtin\":\"companion.settings.open\"}}");
        valid(base + "{\"action\":\"a.b\",\"when_offline\":\"queue\",\"ttl_s\":60.0,\"dedupe\":\"k1\",\"confirm\":{\"text\":\"Sure?\",\"title\":\"T\"}}}");
        // SPEC 14.3: a remote descriptor on a value-producing hook must not
        // author the member the Companion injects.
        invalid("{\"t\":\"text\",\"text\":\"x\",\"on_change\":{\"action\":\"a.b\",\"args\":{\"value\":1}}}", "on_change.args.value");
    }

    @Test public void canvasOps() {
        String base = "{\"t\":\"canvas\",\"width\":10,\"height\":10,\"ops\":";
        valid(base + "[]}");
        valid(base + "[{\"op\":\"line\",\"x1\":0,\"y1\":0,\"x2\":5,\"y2\":5,\"width\":2},{\"op\":\"circle\",\"cx\":5,\"cy\":5,\"radius\":3,\"fill\":\"#0f0\"},{\"op\":\"path\",\"points\":[{\"x\":0,\"y\":0},{\"x\":1,\"y\":1}],\"closed\":true},{\"op\":\"text\",\"x\":1,\"y\":2,\"text\":\"hi\",\"size\":8},{\"op\":\"nova\",\"anything\":true}]}");
        invalid(base + "[{\"op\":\"rect\",\"x\":0,\"y\":0,\"width\":-1,\"height\":1}]}", "width");
        invalid(base + "[{\"op\":\"circle\",\"cx\":0,\"cy\":0}]}", "ops[0]");
        invalid(base + "[{\"op\":\"path\",\"points\":[{\"x\":0}]}]}", "points[0]");
        invalid(base + "[{\"op\":\"text\",\"x\":0,\"y\":0,\"text\":5}]}", "text");
        invalid(base + "[{\"op\":\"rect\",\"x\":0,\"y\":0,\"width\":1,\"height\":1,\"fill\":\"red-ish!\"}]}", "fill");
        invalid("{\"t\":\"canvas\",\"width\":0,\"height\":10,\"ops\":[]}", "width");
        StringBuilder many = new StringBuilder(base + "[");
        for (int i = 0; i < 1025; i++) many.append(i > 0 ? "," : "").append("{\"op\":\"nova\"}");
        invalid(many + "]}", "ops");
    }

    @Test public void multiViewAndDepth() {
        SpecValidator.Result r = valid("{\"views\":{\"a\":{\"t\":\"text\",\"text\":\"A\"},\"b\":{\"t\":\"text\",\"text\":\"B\"}},\"initial_view\":\"a\"}");
        assertTrue(r.multiView);
        invalid("{\"views\":{},\"initial_view\":\"a\"}", "views");
        invalid("{\"views\":{\"a\":{\"t\":\"text\",\"text\":\"A\"}},\"initial_view\":\"zz\"}", "initial_view");
        invalid("{\"views\":{\"a\":{\"t\":\"text\",\"text\":\"A\"}},\"initial_view\":\"a\",\"extra\":1}", "extra");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 21; i++) deep.append("{\"t\":\"column\",\"children\":[");
        deep.append("{\"t\":\"text\",\"text\":\"x\"}");
        for (int i = 0; i < 21; i++) deep.append("]}");
        invalid(deep.toString(), "children");
    }

    @Test public void presentationFlattensInOrder() {
        SurfaceRecord r = new SurfaceRecord("app:milestone", 3, true, Fixtures.milestoneSpec(false), null, null, null, 0, 1);
        Presentation p = Presentation.of(r, ctx.profile);
        assertEquals(5, p.items.size());
        assertEquals("text", p.items.get(0).kind);
        assertEquals("r.0", p.items.get(0).path);
        assertEquals("canvas", p.items.get(2).kind);
        assertEquals("button", p.items.get(3).kind);
        assertEquals("set-on", p.nodeAt("r.3").string("id"));
        Json.Obj doc = p.document(9, new java.util.ArrayList<int[]>());
        assertEquals(Long.valueOf(9), doc.integer("generation"));
        assertEquals("Flag: off", doc.array("items").get(1).asObject().string("text"));
        assertEquals("body", doc.array("items").get(1).asObject().string("style"));
        assertTrue(Vocabulary.CORE_NODE_SET.contains("column"));
    }
}
