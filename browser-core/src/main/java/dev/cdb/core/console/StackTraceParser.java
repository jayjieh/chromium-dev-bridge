package dev.cdb.core.console;

import dev.cdb.protocol.SourceLocation;
import dev.cdb.protocol.StackFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses V8 ("    at fn (url:line:col)") and Firefox/Safari ("fn@url:line:col") stack traces. */
public final class StackTraceParser {
    private static final Pattern V8_WITH_FN = Pattern.compile("^\\s*at\\s+(?:async\\s+)?(.*?)\\s+\\((.+):(\\d+):(\\d+)\\)\\s*$");
    private static final Pattern V8_NO_FN = Pattern.compile("^\\s*at\\s+(?:async\\s+)?(.+):(\\d+):(\\d+)\\s*$");
    private static final Pattern GECKO = Pattern.compile("^\\s*(.*?)@(.+):(\\d+):(\\d+)\\s*$");
    private static final int MAX_FRAMES = 50;

    private StackTraceParser() {
    }

    public static List<StackFrame> parse(String stack) {
        List<StackFrame> frames = new ArrayList<>();
        if (stack == null || stack.isBlank()) return frames;
        for (String line : stack.split("\\r?\\n")) {
            if (frames.size() >= MAX_FRAMES) break;
            Matcher m = V8_WITH_FN.matcher(line);
            if (m.matches()) {
                frames.add(frame(m.group(1), m.group(2), m.group(3), m.group(4)));
                continue;
            }
            m = V8_NO_FN.matcher(line);
            if (m.matches()) {
                frames.add(frame(null, m.group(1), m.group(2), m.group(3)));
                continue;
            }
            m = GECKO.matcher(line);
            if (m.matches() && m.group(2).contains("/")) {
                frames.add(frame(m.group(1).isEmpty() ? null : m.group(1), m.group(2), m.group(3), m.group(4)));
            }
        }
        return frames;
    }

    /** Extracts the message part (lines before the first frame). */
    public static String message(String stackOrMessage) {
        if (stackOrMessage == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : stackOrMessage.split("\\r?\\n")) {
            if (line.trim().startsWith("at ")) break;
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private static StackFrame frame(String fn, String url, String line, String col) {
        return new StackFrame(fn, SourceLocation.ofUrl(url, Integer.parseInt(line), Integer.parseInt(col)), null);
    }
}
