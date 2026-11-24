package dev.cdb.driver;

import dev.cdb.core.session.SessionManager;
import dev.cdb.driver.audit.FileAuditSink;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic housekeeping: idle sessions, disconnected browsers, screenshot and audit retention. */
@Component
class Maintenance {
    private final SessionManager sessions;
    private final FileAuditSink audit;

    Maintenance(SessionManager sessions, FileAuditSink audit) {
        this.sessions = sessions;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT15S")
    void maintain() {
        sessions.maintain();
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT1M")
    void retention() {
        audit.enforceRetention();
    }
}
