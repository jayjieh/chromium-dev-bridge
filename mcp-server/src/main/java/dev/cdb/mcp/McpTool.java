package dev.cdb.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A transport-independent MCP tool. */
public interface McpTool {
    String name();

    String title();

    String description();

    ObjectNode inputSchema();

    ObjectNode outputSchema();

    boolean readOnly();

    boolean destructive();

    ToolResult call(JsonNode arguments);
}
