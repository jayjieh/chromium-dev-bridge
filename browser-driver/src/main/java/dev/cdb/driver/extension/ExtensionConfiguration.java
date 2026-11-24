package dev.cdb.driver.extension;

import dev.cdb.core.BridgeException;
import dev.cdb.core.Principal;
import dev.cdb.core.session.SessionManager;
import dev.cdb.core.spi.SessionOpener;
import dev.cdb.driver.CdbProperties;
import dev.cdb.protocol.ApprovalKind;
import dev.cdb.protocol.ApprovalRequest;
import dev.cdb.provider.extension.ExtensionHub;
import dev.cdb.provider.extension.ExtensionSessionRegistrar;
import dev.cdb.provider.extension.ExtensionTab;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the Chrome extension hub to the session manager. Shared tabs become admin-owned sessions. */
@Configuration
public class ExtensionConfiguration {

    @Bean(destroyMethod = "close")
    ExtensionHub extensionHub(SessionManager sessions, CdbProperties props) {
        ExtensionSessionRegistrar registrar = new ExtensionSessionRegistrar() {
            @Override
            public String attach(ExtensionTab tab, SessionOpener opener) {
                String title = tab.title() == null || tab.title().isBlank() ? "tab " + tab.tabId() : tab.title();
                if (title.length() > 60) title = title.substring(0, 60) + "…";
                return sessions.attach(Principal.admin(), "Chrome: " + title, null, tab.agentAccess(), Duration.ofHours(12), opener)
                        .sessionId();
            }

            @Override
            public void detach(String sessionId) {
                try {
                    sessions.close(Principal.admin(), sessionId);
                } catch (BridgeException ignored) {
                    // already closed
                }
            }

            @Override
            public void approveOrigin(String sessionId, String origin) {
                sessions.approve(Principal.admin(), sessionId, new ApprovalRequest(ApprovalKind.ORIGIN, origin,
                        "approved in the Chrome extension popup"));
            }
        };
        return new ExtensionHub(registrar, props.sessions().operationTimeout().plusSeconds(10), props.security().extensionCanApprove());
    }
}
