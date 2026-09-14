package io.jirrafe.maven;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

/** Minimal JSON writer for maps, collections, strings, numbers, booleans and null. No dependency needed. */
final class Json {
    /** Marks a string that is already JSON and must be embedded verbatim. */
    static final Function<String, Object> RAW = Raw::new;

    private record Raw(String json) {
    }

    private Json() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 0);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Raw raw) {
            sb.append(raw.json().strip());
        } else if (value instanceof String s) {
            quote(s, sb);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                pad(sb, indent + 1);
                quote(String.valueOf(e.getKey()), sb);
                sb.append(": ");
                write(e.getValue(), sb, indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
        } else if (value instanceof Collection<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            boolean first = true;
            for (Object o : list) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                pad(sb, indent + 1);
                write(o, sb, indent + 1);
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append(']');
        } else {
            quote(String.valueOf(value), sb);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("    ".repeat(indent));
    }
}
