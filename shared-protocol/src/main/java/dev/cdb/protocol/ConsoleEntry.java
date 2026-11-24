package dev.cdb.protocol;

import java.time.Instant;
import java.util.List;

public record ConsoleEntry(long seq, String eventId, String sessionId, String tabId, Instant timestamp, ConsoleLevel level,
                           ConsoleKind kind, String message, SourceLocation source, SourceLocation originalSource,
                           List<StackFrame> stack) {
    public ConsoleEntry withSeq(long s) {
        return new ConsoleEntry(s, eventId, sessionId, tabId, timestamp, level, kind, message, source, originalSource, stack);
    }

    public ConsoleEntry withOriginal(SourceLocation original, List<StackFrame> frames) {
        return new ConsoleEntry(seq, eventId, sessionId, tabId, timestamp, level, kind, message, source, original, frames);
    }
}
