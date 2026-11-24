package dev.cdb.core;

import java.util.Map;

/** Receives security-relevant operations. Values must already be redacted. */
@FunctionalInterface
public interface AuditSink {
    void record(Principal principal, String operation, String sessionId, String outcome, Map<String, Object> details);

    AuditSink NOOP = (p, o, s, out, d) -> { };
}
