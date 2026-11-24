package dev.cdb.driver.metrics;

import dev.cdb.core.DriverMetrics;
import dev.cdb.core.session.SessionManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Micrometer implementation of the core metrics hook. Tags never carry page data. */
@Component
public class MicrometerDriverMetrics implements DriverMetrics {
    private final MeterRegistry registry;
    private final Timer startup;
    private final Counter navigationFailures;
    private final Counter crashes;
    private final Counter sessionFailures;
    private final AtomicInteger webSockets = new AtomicInteger();

    public MicrometerDriverMetrics(MeterRegistry registry, ObjectProvider<SessionManager> sessions) {
        this.registry = registry;
        this.startup = Timer.builder("cdb.browser.startup").description("Browser session startup duration").register(registry);
        this.navigationFailures = Counter.builder("cdb.navigation.failures").register(registry);
        this.crashes = Counter.builder("cdb.browser.crashes").description("Browser disconnects / crashes").register(registry);
        this.sessionFailures = Counter.builder("cdb.sessions.failed").register(registry);
        Gauge.builder("cdb.sessions.active", sessions, s -> {
            SessionManager m = s.getIfAvailable();
            return m == null ? 0 : m.activeSessionCount();
        }).register(registry);
        Gauge.builder("cdb.websocket.connections", webSockets, AtomicInteger::get).register(registry);
    }

    @Override
    public void sessionStarted(Duration d) {
        startup.record(d);
    }

    @Override
    public void sessionFailed() {
        sessionFailures.increment();
    }

    @Override
    public void action(String type, Duration duration, boolean success) {
        Timer.builder("cdb.browser.action").tag("type", type).tag("outcome", success ? "success" : "failure")
                .register(registry).record(duration);
    }

    @Override
    public void navigationFailed() {
        navigationFailures.increment();
    }

    @Override
    public void browserCrashed() {
        crashes.increment();
    }

    @Override
    public void bufferUtilization(String buffer, double ratio) {
        DistributionSummary.builder("cdb.buffer.utilization").tag("buffer", buffer).register(registry).record(ratio);
    }

    public void webSocketOpened() {
        webSockets.incrementAndGet();
    }

    public void webSocketClosed() {
        webSockets.decrementAndGet();
    }

    /** Duration of MCP tool invocations, reported by the MCP server through the X-CDB-Tool header. */
    public void mcpTool(String tool, Duration d, int status) {
        Timer.builder("cdb.mcp.tool").tag("tool", tool).tag("outcome", status < 400 ? "success" : "failure")
                .register(registry).record(d);
    }
}
