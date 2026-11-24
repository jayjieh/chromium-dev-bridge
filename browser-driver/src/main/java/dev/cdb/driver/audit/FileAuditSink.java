package dev.cdb.driver.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cdb.core.AuditSink;
import dev.cdb.core.Principal;
import dev.cdb.driver.CdbProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Append-only local audit log: one JSON object per line, one file per day, with retention. */
@Component
public class FileAuditSink implements AuditSink {
    private static final Logger log = LoggerFactory.getLogger(FileAuditSink.class);
    private final Path dir;
    private final boolean enabled;
    private final int retentionDays;
    private final ObjectMapper mapper;

    public FileAuditSink(CdbProperties props, ObjectMapper mapper) {
        this.dir = props.dataDir().resolve("audit");
        this.enabled = props.audit().enabled();
        this.retentionDays = props.audit().retentionDays();
        this.mapper = mapper;
    }

    @Override
    public synchronized void record(Principal principal, String operation, String sessionId, String outcome, Map<String, Object> details) {
        if (!enabled) return;
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", Instant.now().toString());
        line.put("principal", principal.name());
        line.put("operation", operation);
        if (sessionId != null) line.put("sessionId", sessionId);
        line.put("outcome", outcome);
        String corr = MDC.get("correlationId");
        if (corr != null) line.put("correlationId", corr);
        if (details != null && !details.isEmpty()) line.put("details", details);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("audit-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
            Files.writeString(file, mapper.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Audit write failed: {}", e.toString());
        }
    }

    public void enforceRetention() {
        if (!Files.isDirectory(dir)) return;
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().matches("audit-\\d{4}-\\d{2}-\\d{2}\\.jsonl")).forEach(f -> {
                String d = f.getFileName().toString().substring(6, 16);
                if (LocalDate.parse(d).isBefore(cutoff)) {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException e) {
                        log.debug("Could not delete {}: {}", f, e.toString());
                    }
                }
            });
        } catch (IOException e) {
            log.debug("Audit retention failed: {}", e.toString());
        }
    }

    public Path directory() {
        return dir;
    }
}
