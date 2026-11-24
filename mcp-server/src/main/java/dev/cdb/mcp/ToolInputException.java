package dev.cdb.mcp;

/** Invalid tool arguments (reported to the model as a tool error). */
public class ToolInputException extends RuntimeException {
    public ToolInputException(String message) {
        super(message);
    }
}
