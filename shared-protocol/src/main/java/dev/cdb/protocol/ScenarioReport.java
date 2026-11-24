package dev.cdb.protocol;

import java.time.Instant;
import java.util.List;

public record ScenarioReport(String name, String sessionId, boolean success, Instant startedAt, long durationMs, int passed,
                             int failed, int skipped, List<StepResult> steps, List<String> consoleErrors,
                             List<String> failedRequests) {
}
