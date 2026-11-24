package dev.cdb.protocol;

import java.time.Instant;

public record ConsoleQuery(String tabId, ConsoleLevel level, Instant since, String text, String source, Long afterSeq,
                           Integer limit) {
    public static ConsoleQuery all() {
        return new ConsoleQuery(null, null, null, null, null, null, null);
    }

    public static ConsoleQuery minLevel(ConsoleLevel level) {
        return new ConsoleQuery(null, level, null, null, null, null, null);
    }
}
