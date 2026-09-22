#!/usr/bin/env python3
"""Generate the Java wire vocabulary from the canonical EBP contract.

The same pattern as ebp-kmp/tools/gen-vocabulary.py: vocabulary is
generated from the authored contract, never hand-maintained, and a drift
test re-reads contract.json and fails if the committed generated file
disagrees.  Run from the ebp-java root:

    python3 tools/gen-vocabulary.py

Use ``--check`` in verification to fail without rewriting a stale projection.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--spec-dir", type=Path, default=ROOT.parent / "ebp",
                    help="canonical EBP specification checkout (default: sibling ../ebp)")
parser.add_argument("--output", type=Path,
                    default=ROOT / "src/main/java/com/calebc42/ebp/companion/engine/Vocabulary.java",
                    help="Java projection destination")
parser.add_argument("--check", action="store_true",
                    help="fail instead of writing when the committed projection is stale")
options = parser.parse_args()

contract = json.loads((options.spec_dir.resolve() / "contract.json").read_text(encoding="utf-8"))
OUT = options.output.resolve()


def jstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def j_set(values):
    return "setOf(" + ", ".join(jstr(v) for v in values) + ")"


lines = []
emit = lines.append
emit("// SPDX-License-Identifier: GPL-3.0-or-later")
emit("// GENERATED from ebp/contract.json (format %s, spec %s) by"
     % (contract.get("contract_format"), contract.get("spec_version")))
emit("// tools/gen-vocabulary.py — DO NOT EDIT.  VocabularyDriftTest re-reads the")
emit("// contract and fails on any disagreement.")
emit("package com.calebc42.ebp.companion.engine;")
emit("")
emit("import java.util.Arrays;")
emit("import java.util.Collections;")
emit("import java.util.HashMap;")
emit("import java.util.HashSet;")
emit("import java.util.Map;")
emit("import java.util.Set;")
emit("")
emit("public final class Vocabulary {")
emit("    private Vocabulary() {}")
emit("")
emit("    public static final class Row {")
emit("        public final Set<String> required;")
emit("        public final Set<String> optional;")
emit("        Row(Set<String> required, Set<String> optional) { this.required = required; this.optional = optional; }")
emit("        public boolean lists(String member) { return required.contains(member) || optional.contains(member); }")
emit("    }")
emit("")
emit("    private static Set<String> setOf(String... values) {")
emit("        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));")
emit("    }")
emit("")
emit("    public static final int PROTOCOL_VERSION = %d;" % contract["protocol_version"])
emit("    public static final String SPEC_VERSION = %s;" % jstr(contract["spec_version"]))
emit("    public static final int CONTRACT_FORMAT = %d;" % contract["contract_format"])
emit("")
emit("    /** SPEC 16.2: the Core Node Set every conforming Companion implements. */")
emit("    public static final Set<String> CORE_NODE_SET = %s;" % j_set(contract["core_node_set"]))
emit("    /** SPEC 16.5: members that MAY appear on any node. */")
emit("    public static final Set<String> UNIVERSAL_NODE_ATTRIBUTES = %s;" % j_set(contract["universal_node_attributes"]))
emit("    /** SPEC 17.1: members whose value is an ActionDescriptor. */")
emit("    public static final Set<String> ACTION_HOOK_KEYS = %s;" % j_set(contract["actions"]["hook_keys"]))
emit("    public static final Set<String> OFFLINE_POLICIES = %s;" % j_set(contract["actions"]["offline_policies"]))
emit("    public static final String OFFLINE_DEFAULT = %s;" % jstr(contract["actions"]["offline_default"]))
emit("    public static final Set<String> DESCRIPTOR_FIELDS = %s;" % j_set(contract["actions"]["descriptor_fields"]))
emit("    public static final Set<String> SEMANTICS_MEMBERS = %s;" % j_set(contract["semantics_schema"]["optional"]))
emit("    public static final Set<String> RENDERER_PROFILE_REQUIRED = %s;" % j_set(contract["renderer_profile_schema"]["required"]))
emit("    public static final Set<String> RENDERER_PROFILE_OPTIONAL = %s;" % j_set(contract["renderer_profile_schema"]["optional"]))
emit("    public static final Set<String> PROFILE_MEMBERS_KEYS = %s;" % j_set(contract["renderer_profile_schema"]["members"]["required"]))
emit("    public static final Set<String> CAPABILITIES = %s;" % j_set(contract["capabilities"]))
emit("")
emit("    /** SPEC 17: per-node required and optional members. */")
emit("    public static final Map<String, Row> NODE_SCHEMA;")
emit("    /** SPEC 14.1/14.2: per-descriptor-kind required and optional members. */")
emit("    public static final Map<String, Row> ACTION_SCHEMA;")
emit("    /** SPEC 17.1: the type of every member name, for scalar type checks. */")
emit("    public static final Map<String, String> FIELD_TYPES;")
emit("    /** SPEC 12 rule 6: closed enum vocabularies keyed `node.member`. */")
emit("    public static final Map<String, Set<String>> ENUMS;")
emit("    /** SPEC 14.3: members each value-producing hook injects into `args`. */")
emit("    public static final Map<String, Set<String>> INJECTIONS;")
emit("    /** SPEC 8: error code -> data.kind. */")
emit("    public static final Map<Integer, String> ERROR_KINDS;")
emit("    /** SPEC 11: method -> sender|class|state,state... for the drift test. */")
emit("    public static final Map<String, String> METHOD_TABLE;")
emit("    /** SPEC 4.5 fixed limits. */")
emit("    public static final Map<String, Long> LIMITS_FIXED;")
emit("")
emit("    static {")
emit("        Map<String, Row> nodes = new HashMap<String, Row>();")
for name, row in contract["node_schema"].items():
    emit("        nodes.put(%s, new Row(%s, %s));" % (jstr(name), j_set(row["required"]), j_set(row["optional"])))
emit("        NODE_SCHEMA = Collections.unmodifiableMap(nodes);")
emit("        Map<String, Row> actions = new HashMap<String, Row>();")
for name, row in contract["actions"]["schema"].items():
    emit("        actions.put(%s, new Row(%s, %s));" % (jstr(name), j_set(row["required"]), j_set(row["optional"])))
emit("        ACTION_SCHEMA = Collections.unmodifiableMap(actions);")
emit("        Map<String, String> types = new HashMap<String, String>();")
for name, ftype in contract["field_types"].items():
    emit("        types.put(%s, %s);" % (jstr(name), jstr(ftype)))
emit("        FIELD_TYPES = Collections.unmodifiableMap(types);")
emit("        Map<String, Set<String>> enums = new HashMap<String, Set<String>>();")
for name, values in contract["enums"].items():
    emit("        enums.put(%s, %s);" % (jstr(name), j_set(values)))
emit("        ENUMS = Collections.unmodifiableMap(enums);")
emit("        Map<String, Set<String>> injections = new HashMap<String, Set<String>>();")
for name, values in contract["actions"]["injections"].items():
    emit("        injections.put(%s, %s);" % (jstr(name), j_set(values)))
emit("        INJECTIONS = Collections.unmodifiableMap(injections);")
emit("        Map<Integer, String> errors = new HashMap<Integer, String>();")
for code, row in contract["error_codes"].items():
    emit("        errors.put(Integer.valueOf(%d), %s);" % (int(code), jstr(row["kind"])))
emit("        ERROR_KINDS = Collections.unmodifiableMap(errors);")
emit("        Map<String, String> methods = new HashMap<String, String>();")
for name, row in contract["methods"].items():
    emit("        methods.put(%s, %s);" % (jstr(name), jstr("%s|%s|%s" % (row["sender"], row["class"], ",".join(row["states"])))))
emit("        METHOD_TABLE = Collections.unmodifiableMap(methods);")
emit("        Map<String, Long> limits = new HashMap<String, Long>();")
for name, value in contract["limits"]["fixed"].items():
    emit("        limits.put(%s, Long.valueOf(%dL));" % (jstr(name), int(value)))
emit("        LIMITS_FIXED = Collections.unmodifiableMap(limits);")
emit("    }")
emit("}")
text = "\n".join(lines) + "\n"

if options.check:
    current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
    if current != text:
        raise SystemExit("%s is stale; rerun tools/gen-vocabulary.py" % OUT)
    print("vocabulary projection is current")
else:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print("wrote %s" % OUT)
