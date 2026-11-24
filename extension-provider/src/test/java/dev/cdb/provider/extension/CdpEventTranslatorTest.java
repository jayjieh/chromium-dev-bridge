package dev.cdb.provider.extension;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cdb.core.spi.BrowserEventListener;
import dev.cdb.protocol.ConsoleEntry;
import dev.cdb.protocol.ConsoleKind;
import dev.cdb.protocol.ConsoleLevel;
import dev.cdb.protocol.Json;
import dev.cdb.protocol.NetworkEntry;
import dev.cdb.protocol.NetworkState;
import dev.cdb.protocol.TabInfo;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CdpEventTranslatorTest {
    final List<ConsoleEntry> console = new ArrayList<>();
    final List<NetworkEntry> network = new ArrayList<>();
    final CdpEventTranslator t = new CdpEventTranslator("tab-7", new BrowserEventListener() {
        public void onConsole(ConsoleEntry e) { console.add(e); }
        public void onNetwork(NetworkEntry e) { network.add(e); }
        public void onTabCreated(TabInfo tab) { }
        public void onTabClosed(String tabId) { }
        public void onNavigation(String tabId, String url, NavigationPhase phase, String error) { }
        public void onNavigationBlocked(String tabId, String url) { }
        public void onDisconnected(String reason) { }
    });

    static JsonNode j(String s) throws Exception {
        return Json.mapper().readTree(s);
    }

    @Test
    void consoleAndExceptions() throws Exception {
        t.handle("Runtime.consoleAPICalled", j("""
                {"type":"error","args":[{"type":"string","value":"Failed:"},{"type":"number","value":500},
                 {"type":"object","description":"Object"}],
                 "stackTrace":{"callFrames":[{"functionName":"load","url":"http://localhost:3000/app.js","lineNumber":9,"columnNumber":4}]}}
                """), false);
        t.handle("Runtime.exceptionThrown", j("""
                {"exceptionDetails":{"text":"Uncaught","exception":{"description":"TypeError: x is undefined\\n    at render (http://localhost:3000/app.js:29:18)"}}}
                """), false);
        t.handle("Runtime.exceptionThrown", j("""
                {"exceptionDetails":{"text":"Uncaught (in promise)","exception":{"description":"Error: nope\\n    at http://localhost:3000/app.js:3:1"}}}
                """), false);
        t.handle("Log.entryAdded", j("""
                {"entry":{"source":"network","level":"error","text":"Failed to load resource: 500","url":"http://localhost:3000/api"}}
                """), false);
        t.handle("Log.entryAdded", j("{\"entry\":{\"source\":\"other\",\"level\":\"verbose\",\"text\":\"noise\"}}"), false);

        assertThat(console).hasSize(4);
        assertThat(console.get(0).message()).isEqualTo("Failed: 500 Object");
        assertThat(console.get(0).level()).isEqualTo(ConsoleLevel.ERROR);
        assertThat(console.get(0).source().line()).isEqualTo(10);
        assertThat(console.get(0).source().column()).isEqualTo(5);
        assertThat(console.get(1).kind()).isEqualTo(ConsoleKind.PAGE_ERROR);
        assertThat(console.get(1).message()).isEqualTo("TypeError: x is undefined");
        assertThat(console.get(1).source().line()).isEqualTo(29);
        assertThat(console.get(2).kind()).isEqualTo(ConsoleKind.UNHANDLED_REJECTION);
        assertThat(console.get(2).message()).isEqualTo("Unhandled promise rejection: Error: nope");
        assertThat(console.get(3).message()).contains("Failed to load resource");
    }

    @Test
    void networkLifecycleRedirectsAndFailures() throws Exception {
        t.handle("Network.requestWillBeSent", j("""
                {"requestId":"1","loaderId":"L1","type":"Document","timestamp":100.0,"wallTime":1700000000,
                 "request":{"url":"http://localhost:3000/old","method":"GET","headers":{"Accept":"text/html"}}}
                """), false);
        t.handle("Network.requestWillBeSent", j("""
                {"requestId":"1","loaderId":"L1","type":"Document","timestamp":100.05,
                 "redirectResponse":{"status":302,"statusText":"Found","headers":{"Location":"/new"}},
                 "request":{"url":"http://localhost:3000/new","method":"GET","headers":{}}}
                """), false);
        t.handle("Network.responseReceived", j("""
                {"requestId":"1","loaderId":"L1","type":"Document","response":{"status":200,"statusText":"OK","mimeType":"text/html","headers":{"Content-Type":"text/html"}}}
                """), false);
        assertThat(t.inflight()).isEqualTo(1);
        t.handle("Network.loadingFinished", j("{\"requestId\":\"1\",\"timestamp\":100.25}"), false);
        t.handle("Network.requestWillBeSent", j("""
                {"requestId":"2","type":"Fetch","timestamp":101.0,"request":{"url":"http://api.other.test/x","method":"POST","headers":{}}}
                """), false);
        t.handle("Network.loadingFailed", j("""
                {"requestId":"2","timestamp":101.1,"errorText":"net::ERR_FAILED","corsErrorStatus":{"corsError":"MissingAllowOriginHeader"}}
                """), false);

        assertThat(t.documentStatus("L1")).isEqualTo(200);
        assertThat(t.inflight()).isZero();
        List<NetworkEntry> done = network.stream().filter(e -> e.state() != NetworkState.PENDING).toList();
        assertThat(done).hasSize(3);
        assertThat(done.get(0).status()).isEqualTo(302);
        assertThat(done.get(1).url()).endsWith("/new");
        assertThat(done.get(1).redirectedFrom()).endsWith("/old");
        assertThat(done.get(1).durationMs()).isEqualTo(200.0);
        assertThat(done.get(1).resourceType()).isEqualTo("document");
        assertThat(done.get(2).state()).isEqualTo(NetworkState.FAILED);
        assertThat(done.get(2).corsError()).isTrue();
        assertThat(done.get(2).failureText()).contains("MissingAllowOriginHeader");
    }
}
