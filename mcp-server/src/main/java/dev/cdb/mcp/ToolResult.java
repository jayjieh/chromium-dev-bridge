package dev.cdb.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Result of a tool call: human/model-readable text, optional structured content and optional image. */
public record ToolResult(String text, JsonNode structured, String imageBase64, String imageMime, boolean isError) {
    public static ToolResult ok(String text, JsonNode structured) {
        return new ToolResult(text, structured, null, null, false);
    }

    public static ToolResult error(String text) {
        return new ToolResult(text, null, null, null, true);
    }

    public ToolResult withImage(String base64, String mime) {
        return new ToolResult(text, structured, base64, mime, isError);
    }

    public ObjectNode toJson(ObjectMapper mapper, boolean structuredOutput) {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode content = r.putArray("content");
        content.addObject().put("type", "text").put("text", text);
        if (imageBase64 != null) {
            content.addObject().put("type", "image").put("data", imageBase64).put("mimeType", imageMime);
        }
        if (structuredOutput && structured != null && structured.isObject()) r.set("structuredContent", structured);
        r.put("isError", isError);
        return r;
    }
}
