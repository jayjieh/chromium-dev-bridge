package dev.cdb.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cdb.protocol.Json;

/** Small JSON Schema builder for tool definitions. */
final class Schemas {
    static final ObjectMapper M = Json.mapper();

    private final ObjectNode schema = M.createObjectNode();
    private final ObjectNode props;
    private final ArrayNode required;

    private Schemas() {
        schema.put("type", "object");
        props = schema.putObject("properties");
        required = M.createArrayNode();
    }

    static Schemas object() {
        return new Schemas();
    }

    Schemas str(String name, String description, boolean req) {
        props.putObject(name).put("type", "string").put("description", description);
        if (req) required.add(name);
        return this;
    }

    Schemas enumStr(String name, String description, boolean req, String... values) {
        ObjectNode n = props.putObject(name).put("type", "string").put("description", description);
        ArrayNode e = n.putArray("enum");
        for (String v : values) e.add(v);
        if (req) required.add(name);
        return this;
    }

    Schemas integer(String name, String description, int min, int max) {
        props.putObject(name).put("type", "integer").put("description", description).put("minimum", min).put("maximum", max);
        return this;
    }

    Schemas number(String name, String description, double min, double max) {
        props.putObject(name).put("type", "number").put("description", description).put("minimum", min).put("maximum", max);
        return this;
    }

    Schemas bool(String name, String description) {
        props.putObject(name).put("type", "boolean").put("description", description);
        return this;
    }

    Schemas strings(String name, String description, boolean req) {
        ObjectNode n = props.putObject(name).put("type", "array").put("description", description);
        n.putObject("items").put("type", "string");
        if (req) required.add(name);
        return this;
    }

    Schemas object(String name, String description, ObjectNode s, boolean req) {
        ObjectNode copy = s.deepCopy();
        copy.put("description", description);
        props.set(name, copy);
        if (req) required.add(name);
        return this;
    }

    Schemas session() {
        return str("sessionId", "Session id returned by browser_create_session", true);
    }

    Schemas target(boolean req) {
        str("ref", "Element reference from browser_snapshot, e.g. element-12 (preferred)", false);
        object("locator", "Semantic locator if no ref: role+name, testId, label, placeholder, text, css or xpath (last resort)"
                + (req ? "" : "; optional"), locatorSchema(), false);
        return this;
    }

    Schemas tab() {
        return str("tabId", "Tab id (defaults to the active tab)", false);
    }

    ObjectNode build() {
        if (!required.isEmpty()) schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    static ObjectNode locatorSchema() {
        ObjectNode s = M.createObjectNode().put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("role").put("type", "string").put("description", "ARIA role, e.g. button, textbox, link");
        p.putObject("name").put("type", "string").put("description", "Accessible name used with role");
        p.putObject("exact").put("type", "boolean");
        p.putObject("testId").put("type", "string").put("description", "data-testid value");
        p.putObject("label").put("type", "string");
        p.putObject("placeholder").put("type", "string");
        p.putObject("text").put("type", "string");
        p.putObject("css").put("type", "string");
        p.putObject("xpath").put("type", "string");
        p.putObject("nth").put("type", "integer").put("minimum", 0);
        s.put("additionalProperties", false);
        return s;
    }

    /** Loose output schema: an object with the listed top-level properties. */
    static ObjectNode output(String... properties) {
        ObjectNode s = M.createObjectNode().put("type", "object");
        ObjectNode p = s.putObject("properties");
        for (String name : properties) p.putObject(name);
        s.put("additionalProperties", true);
        return s;
    }
}
