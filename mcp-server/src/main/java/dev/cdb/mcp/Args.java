package dev.cdb.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cdb.protocol.Locator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Typed, validated access to tool arguments. */
final class Args {
    private final JsonNode node;

    Args(JsonNode node) {
        this.node = node;
    }

    String str(String name) {
        JsonNode n = node.get(name);
        if (n == null || n.isNull()) return null;
        if (!n.isTextual()) throw new ToolInputException(name + " must be a string");
        String s = n.asText();
        if (s.length() > 20_000) throw new ToolInputException(name + " is too long");
        return s;
    }

    String requireStr(String name) {
        String s = str(name);
        if (s == null || s.isBlank()) throw new ToolInputException(name + " is required");
        return s;
    }

    Integer integer(String name, int min, int max) {
        JsonNode n = node.get(name);
        if (n == null || n.isNull()) return null;
        if (!n.canConvertToInt() || !(n.isNumber() || n.isTextual())) throw new ToolInputException(name + " must be an integer");
        int v = n.isTextual() ? parse(name, n.asText()) : n.asInt();
        if (v < min || v > max) throw new ToolInputException(name + " must be between " + min + " and " + max);
        return v;
    }

    Double number(String name, double min, double max) {
        JsonNode n = node.get(name);
        if (n == null || n.isNull()) return null;
        if (!n.isNumber()) throw new ToolInputException(name + " must be a number");
        double v = n.asDouble();
        if (v < min || v > max) throw new ToolInputException(name + " must be between " + min + " and " + max);
        return v;
    }

    Long longValue(String name) {
        JsonNode n = node.get(name);
        if (n == null || n.isNull()) return null;
        if (!n.canConvertToLong()) throw new ToolInputException(name + " must be an integer");
        return n.asLong();
    }

    Boolean bool(String name) {
        JsonNode n = node.get(name);
        if (n == null || n.isNull()) return null;
        if (!n.isBoolean()) throw new ToolInputException(name + " must be a boolean");
        return n.asBoolean();
    }

    String enumValue(String name, Set<String> allowed) {
        String s = str(name);
        if (s == null) return null;
        String u = s.toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) throw new ToolInputException(name + " must be one of " + allowed);
        return u;
    }

    List<String> strings(String name) {
        JsonNode n = node.get(name);
        if (n == null || n.isNull()) return null;
        if (!n.isArray()) throw new ToolInputException(name + " must be an array of strings");
        List<String> out = new ArrayList<>();
        for (JsonNode v : n) {
            if (!v.isTextual()) throw new ToolInputException(name + " must contain strings");
            out.add(v.asText());
        }
        return out;
    }

    JsonNode raw(String name) {
        return node.get(name);
    }

    /** Builds a locator from "ref" or a "locator" object. */
    Locator locator(boolean required) {
        String ref = str("ref");
        JsonNode l = node.get("locator");
        if (ref != null && !ref.isBlank()) return Locator.ref(ref.trim());
        if (l != null && l.isObject()) {
            Args a = new Args(l);
            Locator loc = new Locator(a.str("ref"), a.str("role"), a.str("name"), a.bool("exact"), a.str("testId"), a.str("label"),
                    a.str("placeholder"), a.str("text"), a.str("css"), a.str("xpath"), a.integer("nth", 0, 10_000));
            if (!loc.isEmpty()) return loc;
        }
        if (required) throw new ToolInputException("Provide ref (from browser_snapshot) or a locator object");
        return null;
    }

    private static int parse(String name, String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new ToolInputException(name + " must be an integer");
        }
    }
}
