package dev.cdb.driver;

import dev.cdb.core.AuditSink;
import dev.cdb.core.Clock;
import dev.cdb.core.CoreSettings;
import dev.cdb.core.DriverMetrics;
import dev.cdb.core.events.EventBus;
import dev.cdb.core.scenario.ScenarioRunner;
import dev.cdb.core.session.SessionManager;
import dev.cdb.core.spi.BrowserProvider;
import dev.cdb.protocol.Viewport;
import dev.cdb.provider.playwright.PlaywrightProvider;
import dev.cdb.provider.playwright.PlaywrightSettings;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free core engine into Spring. */
@Configuration
public class CoreConfiguration {

    @Bean
    CoreSettings coreSettings(CdbProperties p) {
        return new CoreSettings(p.dataDir(), p.sessions().max(), p.sessions().idleTimeout(), p.sessions().operationTimeout(),
                p.sessions().navigationTimeout(), p.sessions().consoleBuffer(), p.sessions().networkBuffer(), p.capture().bodies(),
                p.capture().maxBodyBytes(), p.security().allowedHosts(), p.security().allowFileUrls(), p.security().allowEvaluate(),
                p.screenshots().perSession(), p.screenshots().maxAge(), p.security().acceptDownloads(), p.browser().headless(),
                new Viewport(p.browser().viewportWidth(), p.browser().viewportHeight()), p.sessions().autoRestart(),
                p.capture().sourceMaps());
    }

    @Bean(destroyMethod = "")
    BrowserProvider browserProvider(CdbProperties p) {
        CdbProperties.Browser b = p.browser();
        return new PlaywrightProvider(new PlaywrightSettings(b.executablePath(), blankToNull(b.channel()),
                b.args() == null ? List.of() : b.args(), b.install(), b.pumpInterval(), p.security().enforceNavigationGuard()));
    }

    @Bean
    EventBus eventBus() {
        return new EventBus();
    }

    @Bean(destroyMethod = "close")
    SessionManager sessionManager(CoreSettings settings, BrowserProvider provider, EventBus bus, DriverMetrics metrics,
                                  AuditSink audit) {
        return new SessionManager(settings, provider, bus, metrics, audit, Clock.SYSTEM);
    }

    @Bean
    ScenarioRunner scenarioRunner(SessionManager sessions) {
        return new ScenarioRunner(sessions);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
