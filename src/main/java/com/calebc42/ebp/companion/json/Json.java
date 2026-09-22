// SPDX-License-Identifier: GPL-3.0-or-later
// The EBP JSON value model (SPEC 4.1-4.3), a strict serializer, and the
// SPEC 4.3 structural comparator.  Java 7, no library dependency: the
// Emacs Android build compiles with --release 7.
package com.calebc42.ebp.companion.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The seven SPEC 4.2 value kinds, closed.  Integers and binary64 numbers
 * are distinct on the wire and distinct here. */
public abstract class Json {

    /** SPEC 4.2: the largest integer either endpoint may carry, 2^53 - 1. */
    public static final long MAX_SAFE_INTEGER = 9007199254740991L;

    private Json() {}

    public abstract static class Value {
        public boolean isObject() { return false; }
        public boolean isArray() { return false; }
        public boolean isString() { return false; }
        public boolean isNumber() { return false; }
        public boolean isBoolean() { return false; }
        public boolean isNull() { return false; }

        public Obj asObject() { throw new ClassCastException("not an object"); }
        public Arr asArray() { throw new ClassCastException("not an array"); }
        public String asString() { throw new ClassCastException("not a string"); }
        public double asDouble() { throw new ClassCastException("not a number"); }
        public boolean asBoolean() { throw new ClassCastException("not a boolean"); }

        /** The string when this is a JSON string, else null (never coerced). */
        public String stringOrNull() { return isString() ? asString() : null; }

        /** The binary64 value when this is a JSON number, else null. */
        public Double doubleOrNull() { return isNumber() ? Double.valueOf(asDouble()) : null; }

        /** The value when this is an integer-SPELLED JSON number within the
         * safe range, else null: `7.0` and `"7"` are not integers here. */
        public Long integerOrNull() {
            if (!(this instanceof Num)) return null;
            Num n = (Num) this;
            return n.integral ? Long.valueOf(n.longValue) : null;
        }

        /** The value of a JSON number whose VALUE is integral, however the
         * sender spelled it (`60` and `60.0` both read 60), else null. */
        public Long integralOrNull() {
            if (!(this instanceof Num)) return null;
            Num n = (Num) this;
            if (n.integral) return Long.valueOf(n.longValue);
            double d = n.doubleValue;
            if (Double.isInfinite(d) || Double.isNaN(d) || d != Math.floor(d)) return null;
            if (d > MAX_SAFE_INTEGER || d < -MAX_SAFE_INTEGER) return null;
            return Long.valueOf((long) d);
        }

        public Boolean booleanOrNull() { return isBoolean() ? Boolean.valueOf(asBoolean()) : null; }

        @Override public final boolean equals(Object o) {
            return o instanceof Value && structurallyEqual(this, (Value) o);
        }

        @Override public final int hashCode() { return structuralHash(this); }

        @Override public final String toString() { return Json.write(this); }
    }

    public static final class Obj extends Value {
        private final LinkedHashMap<String, Value> members = new LinkedHashMap<String, Value>();

        public Obj() {}

        @Override public boolean isObject() { return true; }
        @Override public Obj asObject() { return this; }

        /** Builder-style insertion; a value tree is not mutated after it has
         * been handed to the engine.  Returns this. */
        public Obj put(String key, Value value) {
            if (key == null || value == null) throw new NullPointerException();
            members.put(key, value);
            return this;
        }
        public Obj put(String key, String value) { return put(key, new Str(value)); }
        public Obj put(String key, long value) { return put(key, Num.of(value)); }
        public Obj put(String key, boolean value) { return put(key, value ? Bool.TRUE : Bool.FALSE); }

        public Obj without(String key) {
            Obj copy = new Obj();
            for (Map.Entry<String, Value> e : members.entrySet())
                if (!e.getKey().equals(key)) copy.members.put(e.getKey(), e.getValue());
            return copy;
        }

        public Obj with(String key, Value value) {
            Obj copy = new Obj();
            copy.members.putAll(members);
            copy.members.put(key, value);
            return copy;
        }

        public boolean has(String key) { return members.containsKey(key); }
        public Value get(String key) { return members.get(key); }
        public int size() { return members.size(); }
        public Set<String> keys() { return Collections.unmodifiableSet(members.keySet()); }
        public Set<Map.Entry<String, Value>> entries() {
            return Collections.unmodifiableSet(members.entrySet());
        }

        // --- typed readers: absent and wrong-typed both read as null ------
        public String string(String key) { Value v = members.get(key); return v == null ? null : v.stringOrNull(); }
        public Obj object(String key) { Value v = members.get(key); return v instanceof Obj ? (Obj) v : null; }
        public Arr array(String key) { Value v = members.get(key); return v instanceof Arr ? (Arr) v : null; }
        public Long integer(String key) { Value v = members.get(key); return v == null ? null : v.integerOrNull(); }
        public Long integral(String key) { Value v = members.get(key); return v == null ? null : v.integralOrNull(); }
        public Double number(String key) { Value v = members.get(key); return v == null ? null : v.doubleOrNull(); }
        public Boolean bool(String key) { Value v = members.get(key); return v == null ? null : v.booleanOrNull(); }
        public boolean boolOr(String key, boolean d) { Boolean b = bool(key); return b == null ? d : b.booleanValue(); }
        public String stringOr(String key, String d) { String s = string(key); return s == null ? d : s; }
        public boolean isNullOrAbsent(String key) { Value v = members.get(key); return v == null || v.isNull(); }
    }

    public static final class Arr extends Value {
        private final ArrayList<Value> items = new ArrayList<Value>();

        public Arr() {}

        @Override public boolean isArray() { return true; }
        @Override public Arr asArray() { return this; }

        public Arr add(Value v) { if (v == null) throw new NullPointerException(); items.add(v); return this; }
        public Arr add(String s) { return add(new Str(s)); }
        public Arr add(long n) { return add(Num.of(n)); }
        public Value get(int i) { return items.get(i); }
        public int size() { return items.size(); }
        public List<Value> items() { return Collections.unmodifiableList(items); }
    }

    public static final class Str extends Value {
        public final String value;
        public Str(String value) { if (value == null) throw new NullPointerException(); this.value = value; }
        @Override public boolean isString() { return true; }
        @Override public String asString() { return value; }
    }

    public static final class Num extends Value {
        final boolean integral;
        final long longValue;
        final double doubleValue;

        private Num(boolean integral, long l, double d) {
            this.integral = integral; this.longValue = l; this.doubleValue = d;
        }
        public static Num of(long v) {
            if (v > MAX_SAFE_INTEGER || v < -MAX_SAFE_INTEGER)
                throw new IllegalArgumentException("integer outside the EBP safe range");
            return new Num(true, v, (double) v);
        }
        public static Num of(double v) {
            if (Double.isNaN(v) || Double.isInfinite(v))
                throw new IllegalArgumentException("non-finite number");
            return new Num(false, 0L, v);
        }
        public boolean isIntegral() { return integral; }
        @Override public boolean isNumber() { return true; }
        @Override public double asDouble() { return doubleValue; }
    }

    public static final class Bool extends Value {
        public static final Bool TRUE = new Bool(true);
        public static final Bool FALSE = new Bool(false);
        public final boolean value;
        private Bool(boolean v) { value = v; }
        public static Bool of(boolean v) { return v ? TRUE : FALSE; }
        @Override public boolean isBoolean() { return true; }
        @Override public boolean asBoolean() { return value; }
    }

    public static final class Null extends Value {
        public static final Null NULL = new Null();
        private Null() {}
        @Override public boolean isNull() { return true; }
    }

    // ------------------------------------------------------------ equality

    /** SPEC 4.3 structural equality: numbers by binary64 value (`1`, `1.0`,
     * `1e0` are equal; `-0` equals `0`), objects order-insensitive, and
     * never across kinds (a boolean is not an integer, a string spelling a
     * number is not that number). */
    public static boolean structurallyEqual(Value a, Value b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Num) {
            return b instanceof Num && ((Num) a).doubleValue == ((Num) b).doubleValue;
        }
        if (a instanceof Str) return b instanceof Str && ((Str) a).value.equals(((Str) b).value);
        if (a instanceof Bool) return b instanceof Bool && ((Bool) a).value == ((Bool) b).value;
        if (a instanceof Null) return b instanceof Null;
        if (a instanceof Arr) {
            if (!(b instanceof Arr)) return false;
            Arr x = (Arr) a, y = (Arr) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++)
                if (!structurallyEqual(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Obj) {
            if (!(b instanceof Obj)) return false;
            Obj x = (Obj) a, y = (Obj) b;
            if (x.size() != y.size()) return false;
            for (Map.Entry<String, Value> e : x.members.entrySet()) {
                Value other = y.members.get(e.getKey());
                if (other == null || !structurallyEqual(e.getValue(), other)) return false;
            }
            return true;
        }
        return false;
    }

    private static int structuralHash(Value v) {
        if (v instanceof Num) {
            double d = ((Num) v).doubleValue;
            if (d == 0.0) d = 0.0; // -0 hashes with 0 (SPEC 4.3)
            long bits = Double.doubleToLongBits(d);
            return 31 + (int) (bits ^ (bits >>> 32));
        }
        if (v instanceof Str) return 37 + ((Str) v).value.hashCode();
        if (v instanceof Bool) return ((Bool) v).value ? 41 : 43;
        if (v instanceof Null) return 47;
        if (v instanceof Arr) {
            int h = 53;
            for (Value item : ((Arr) v).items) h = h * 31 + structuralHash(item);
            return h;
        }
        Obj o = (Obj) v;
        int h = 59;
        for (Map.Entry<String, Value> e : o.members.entrySet())
            h += e.getKey().hashCode() ^ structuralHash(e.getValue()); // order-insensitive
        return h;
    }

    // ---------------------------------------------------------- serializer

    /** Serialize to the exact text placed on the wire (SPEC 4.1: UTF-8, no
     * ill-formed Unicode; non-ASCII is emitted literally, control
     * characters are escaped).  Throws IllegalStateException for a tree
     * that cannot be serialized (should be unreachable for parsed trees). */
    public static String write(Value v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, 0);
        return sb.toString();
    }

    public static byte[] writeUtf8(Value v) {
        return write(v).getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Value v, StringBuilder sb, int depth) {
        if (depth > 512) throw new IllegalStateException("serialization depth exceeded");
        if (v instanceof Obj) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Value> e : ((Obj) v).members.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb, depth + 1);
            }
            sb.append('}');
        } else if (v instanceof Arr) {
            sb.append('[');
            boolean first = true;
            for (Value item : ((Arr) v).items) {
                if (!first) sb.append(',');
                first = false;
                write(item, sb, depth + 1);
            }
            sb.append(']');
        } else if (v instanceof Str) {
            writeString(((Str) v).value, sb);
        } else if (v instanceof Num) {
            Num n = (Num) v;
            if (n.integral) sb.append(n.longValue);
            else {
                if (Double.isNaN(n.doubleValue) || Double.isInfinite(n.doubleValue))
                    throw new IllegalStateException("non-finite number");
                sb.append(Double.toString(n.doubleValue));
            }
        } else if (v instanceof Bool) {
            sb.append(((Bool) v).value ? "true" : "false");
        } else if (v instanceof Null) {
            sb.append("null");
        } else {
            throw new IllegalStateException("unknown value kind");
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else if (Character.isSurrogate(c)) {
                        // SPEC 4.1: a sender MUST NOT emit ill-formed Unicode.
                        if (Character.isHighSurrogate(c) && i + 1 < n
                            && Character.isLowSurrogate(s.charAt(i + 1))) {
                            sb.append(c).append(s.charAt(i + 1));
                            i++;
                        } else {
                            throw new IllegalStateException("unpaired surrogate in output string");
                        }
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** UTF-8 octet length of S, the SPEC 4.5 sizing unit. */
    public static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
