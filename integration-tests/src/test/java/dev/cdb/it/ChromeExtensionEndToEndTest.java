package dev.cdb.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.cdb.client.BrowserDriverClient;
import dev.cdb.client.DriverApiException;
import dev.cdb.driver.DriverApplication;
import dev.cdb.driver.security.TokenService;
import dev.cdb.protocol.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The real unpacked extension (chrome-extension/) loaded into Chromium, paired with the real driver over its WebSocket,
 * sharing a real tab, then driven through the public REST API with the agent token.
 */
@SpringBootTest(classes = DriverApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cdb.data-dir=target/it-data-ext-e2e"})
class ChromeExtensionEndToEndTest {
    static SampleApp app;

    static final String EXTENSION_ID = "cccfpifbammfpjegnpoehanofanhalaj"; // fixed by the "key" in manifest.json

    static String state(Page popup) {
        Map<?, ?> r = (Map<?, ?>) popup.evaluate("() => chrome.runtime.sendMessage({type: 'status'})");
        return String.valueOf(((Map<?, ?>) r.get("result")).get("state"));
    }

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
    void shareTabFromRealExtensionAndDriveIt() throws Exception {
        Path extension = Path.of(System.getProperty("cdb.extensionDir", "../chrome-extension")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(extension.resolve("manifest.json")), "extension not found");
        Path profile = Files.createTempDirectory("cdb-ext-profile");
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            BrowserContext context = pw.chromium().launchPersistentContext(profile, new BrowserType.LaunchPersistentContextOptions()
                    .setChannel("chromium").setHeadless(true)
                    .setArgs(List.of("--disable-extensions-except=" + extension, "--load-extension=" + extension)));
            // Drive the extension the way its popup does (chrome.runtime messages to the service worker).
            Page popup = context.newPage();
            popup.navigate("chrome-extension://" + EXTENSION_ID + "/popup.html");

            // Pair (what the user does by pasting the code into the popup).
            String code = "127.0.0.1:" + port + "#" + tokens.extensionToken();
            Map<?, ?> paired = (Map<?, ?>) popup.evaluate("code => chrome.runtime.sendMessage({type: 'pair', code})", code);
            assertThat(paired.get("ok")).as(String.valueOf(paired)).isEqualTo(true);
            long deadline = System.currentTimeMillis() + 10_000;
            while (!"connected".equals(state(popup)) && System.currentTimeMillis() < deadline) Thread.sleep(100);
            assertThat(state(popup)).isEqualTo("connected");

            // The user opens their app and clicks "Share this tab".
            Page page = context.newPage();
            page.navigate(app.url + "/login");
            Number tabId = (Number) popup.evaluate("async url => (await chrome.tabs.query({})).find(t => t.url === url).id", app.url + "/login");
            Map<?, ?> shared = (Map<?, ?>) popup.evaluate(
                    "tabId => chrome.runtime.sendMessage({type: 'share', tabId, options: {agentAccess: true}})", tabId);
            assertThat(shared.get("ok")).as(String.valueOf(shared)).isEqualTo(true);
            String sessionId = null;
            deadline = System.currentTimeMillis() + 10_000;
            while (sessionId == null && System.currentTimeMillis() < deadline) {
                BrowserDriverClient admin = new BrowserDriverClient("http://127.0.0.1:" + port, tokens.adminToken(), "e2e-admin");
                sessionId = admin.listSessions().stream().filter(s -> s.provider() != null && "chrome-extension".equals(s.provider().id()))
                        .map(SessionInfo::sessionId).findFirst().orElse(null);
                if (sessionId == null) Thread.sleep(100);
            }
            assertThat(sessionId).startsWith("session-");

            // An AI agent drives the user's tab through the normal API.
            BrowserDriverClient agent = new BrowserDriverClient("http://127.0.0.1:" + port, tokens.agentToken(), "e2e-agent");
            SessionInfo info = agent.getSession(sessionId);
            assertThat(info.provider().id()).isEqualTo("chrome-extension");
            assertThat(info.provider().browserVersion()).isNotBlank();

            DomSnapshot snap = agent.dom(sessionId, SnapshotRequest.of(SnapshotMode.INTERACTIVE));
            assertThat(snap.elements()).extracting(DomElement::name).contains("Email", "Sign In");
            agent.perform(sessionId, ActionRequest.fill(Locator.label("Email"), "qa@example.test"));
            agent.perform(sessionId, ActionRequest.fill(Locator.testId("password"), "demo-password"));
            ActionResult click = agent.perform(sessionId, ActionRequest.of(ActionType.CLICK, Locator.role("button", "Sign In")));
            assertThat(click.success()).as(click.message()).isTrue();
            assertThat(agent.waitFor(sessionId, new WaitRequest(null, null, null, null, null, "1284", 10_000)).satisfied()).isTrue();
            // The same tab the user sees is on the dashboard.
            page.waitForURL("**/dashboard");

            deadline = System.currentTimeMillis() + 10_000;
            EventPage<NetworkEntry> failed;
            do {
                failed = agent.network(sessionId, NetworkQuery.failed());
                Thread.sleep(150);
            } while (failed.items().isEmpty() && System.currentTimeMillis() < deadline);
            assertThat(failed.items()).anyMatch(e -> e.url().endsWith("/api/notifications"));
            assertThat(agent.console(sessionId, ConsoleQuery.minLevel(ConsoleLevel.ERROR)).items()).isNotEmpty();
            assertThat(agent.preview(sessionId, null, 50).length).isGreaterThan(1000);

            // The user stops sharing in the popup: the session ends, the tab stays open.
            popup.evaluate("tabId => chrome.runtime.sendMessage({type: 'unshare', tabId})", tabId);
            deadline = System.currentTimeMillis() + 10_000;
            boolean gone = false;
            while (!gone && System.currentTimeMillis() < deadline) {
                try {
                    agent.getSession(sessionId);
                    Thread.sleep(100);
                } catch (DriverApiException e) {
                    gone = e.code().equals("SESSION_NOT_FOUND");
                }
            }
            assertThat(gone).isTrue();
            assertThat(page.isClosed()).isFalse();
            context.close();
        }
    }
}
