package dev.cdb.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.cdb.protocol.Json;
import dev.cdb.provider.extension.ExtensionHub;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Stand-in for the Chrome extension: relays the hub's cdp.send requests to a real Chromium tab through a Playwright
 * CDPSession and forwards CDP events back, exactly like background.js does with chrome.debugger.
 * Owns its Playwright instance on one thread (Playwright is single-threaded).
 */
final class FakeChromeExtension implements AutoCloseable {
    static final List<String> EVENTS = List.of("Page.frameNavigated", "Page.frameStartedLoading", "Page.domContentEventFired",
            "Page.loadEventFired", "Page.navigatedWithinDocument", "Runtime.consoleAPICalled", "Runtime.exceptionThrown",
            "Log.entryAdded", "Network.requestWillBeSent", "Network.responseReceived", "Network.loadingFinished",
            "Network.loadingFailed", "Network.webSocketCreated", "Network.webSocketClosed", "Inspector.detached");

    private final ObjectMapper m = Json.mapper();
    private final ExtensionHub hub;
    private final BlockingQueue<String> toBrowser = new LinkedBlockingQueue<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Thread thread;
    private volatile boolean running = true;
    final int tabId = 7;
    final ExtensionHub.Transport transport = new ExtensionHub.Transport() {
        public String id() { return "fake-extension"; }
        public void send(String text) { toBrowser.add(text); }
        public void close() { running = false; }
    };

    FakeChromeExtension(ExtensionHub hub) throws InterruptedException {
        this.hub = hub;
        this.thread = new Thread(this::loop, "fake-extension");
        thread.start();
        if (!ready.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("Browser did not start");
    }

    private void loop() {
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            CDPSession cdp = page.context().newCDPSession(page);
            for (String event : EVENTS) {
                cdp.on(event, params -> {
                    ObjectNode n = m.createObjectNode().put("jsonrpc", "2.0").put("method", "cdp.event");
                    ObjectNode p = n.putObject("params").put("tabId", tabId).put("method", event);
                    try {
                        p.set("params", params == null ? m.createObjectNode() : m.readTree(params.toString()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    hub.received(transport, n.toString());
                });
            }
            hub.connected(transport);
            ready.countDown();
            while (running) {
                String out = toBrowser.poll(10, TimeUnit.MILLISECONDS);
                if (out == null) {
                    page.waitForTimeout(1); // pump CDP events
                    continue;
                }
                JsonNode msg = m.readTree(out);
                if ("user.navigate".equals(msg.path("method").asText())) {
                    page.navigate(msg.path("url").asText());
                    continue;
                }
                if (!"cdp.send".equals(msg.path("method").asText())) continue; // notifications (tab.unshare, session.attached)
                ObjectNode reply = m.createObjectNode().put("jsonrpc", "2.0");
                reply.set("id", msg.get("id"));
                try {
                    JsonObject params = JsonParser.parseString(msg.at("/params/params").toString()).getAsJsonObject();
                    JsonObject result = cdp.send(msg.at("/params/method").asText(), params);
                    reply.set("result", result == null ? m.createObjectNode() : m.readTree(result.toString()));
                } catch (RuntimeException e) {
                    reply.putObject("error").put("code", -32000).put("message", e.getMessage());
                }
                hub.received(transport, reply.toString());
            }
            browser.close();
        } catch (Exception e) {
            ready.countDown();
            if (running) throw new RuntimeException(e);
        }
    }

    /** Simulates the user navigating their tab themselves (outside the driver's URL policy). */
    void userNavigate(String url) {
        toBrowser.add(m.createObjectNode().put("method", "user.navigate").put("url", url).toString());
    }

    /** Simulates the user clicking "Share this tab". */
    void share(String url, boolean agentAccess) {
        hub.received(transport, m.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tab.shared")
                .set("params", m.createObjectNode().put("tabId", tabId).put("url", url).put("title", "Shared tab")
                        .put("agentAccess", agentAccess)).toString());
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        hub.disconnected(transport);
        thread.join(15_000);
    }
}
