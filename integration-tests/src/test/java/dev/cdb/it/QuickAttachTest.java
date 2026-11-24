package dev.cdb.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.cdb.client.BrowserDriverClient;
import dev.cdb.client.DriverApiException;
import dev.cdb.driver.DriverApplication;
import dev.cdb.driver.security.TokenService;
import dev.cdb.protocol.*;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Quick attach: a Chrome started with --remote-debugging-port (as scripts/*&#47;start-chrome-debug do) is attached over
 * CDP. The session sees the tabs that are already open and only disconnects on close.
 */
@SpringBootTest(classes = DriverApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cdb.data-dir=target/it-data-attach"})
class QuickAttachTest {
    static SampleApp app;

    @LocalServerPort
    int port;
    @Autowired
    TokenService tokens;

    @BeforeAll
    static void startApp() throws Exception {
        app = SampleApp.start();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.close();
    }

    @Test
    void attachesToExistingTabsAndLeavesThemOpen() throws Exception {
        int debugPort;
        try (ServerSocket s = new ServerSocket(0)) {
            debugPort = s.getLocalPort();
        }
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser userChrome = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)
                    .setArgs(List.of("--remote-debugging-port=" + debugPort, "--remote-debugging-address=127.0.0.1")));
            Page userTab = userChrome.newPage();
            userTab.navigate(app.url + "/register");

            BrowserDriverClient admin = new BrowserDriverClient("http://127.0.0.1:" + port, tokens.adminToken(), "it-admin");
            BrowserDriverClient agent = new BrowserDriverClient("http://127.0.0.1:" + port, tokens.agentToken(), "it-agent");

            // Agents may not attach to a browser; the human (admin token) can.
            assertThatThrownBy(() -> agent.createSession(SessionCreateRequest.builder().connectEndpoint("http://127.0.0.1:" + debugPort).build()))
                    .isInstanceOf(DriverApiException.class);
            SessionInfo s = admin.createSession(SessionCreateRequest.builder().connectEndpoint("http://127.0.0.1:" + debugPort)
                    .agentAccess(true).build());
            assertThat(s.provider().name()).isEqualTo("Playwright CDP attach");

            List<TabInfo> tabs = admin.tabs(s.sessionId());
            TabInfo existing = tabs.stream().filter(t -> t.url().endsWith("/register")).findFirst().orElseThrow();
            admin.activateTab(s.sessionId(), existing.tabId());
            DomSnapshot snap = agent.dom(s.sessionId(), SnapshotRequest.of(SnapshotMode.INTERACTIVE));
            assertThat(snap.pageTitle()).isEqualTo("Register");
            assertThat(snap.elements()).extracting(DomElement::name).contains("Create account");
            ActionResult fill = agent.perform(s.sessionId(), ActionRequest.fill(Locator.label("Full name"), "Ada"));
            assertThat(fill.success()).as(fill.message()).isTrue();

            admin.closeSession(s.sessionId());
            // The user's tab is still open and keeps the value typed through the bridge.
            assertThat(userTab.isClosed()).isFalse();
            assertThat(userTab.inputValue("#r-name")).isEqualTo("Ada");
            userChrome.close();
        }
    }
}
