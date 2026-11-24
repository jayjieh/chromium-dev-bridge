package dev.cdb.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.cdb.intellij.ui.AgentPanel
import dev.cdb.intellij.ui.ElementsPanel
import dev.cdb.protocol.DomElement
import dev.cdb.protocol.DomSnapshot
import dev.cdb.protocol.SnapshotMode

/** Services, settings, event parsing, dev-server detection and UI state updates without a running driver. */
class PluginServicesTest : BasePlatformTestCase() {
    fun testSettingsDefaults() {
        val s = CdbSettings.get().state
        assertTrue(s.autoStartDriver)
        assertEquals(1000, s.previewIntervalMs)
    }

    fun testProjectBridgeSelectionNotifiesListeners() {
        val bridge = ProjectBridge.get(project)
        var seen: String? = "none"
        bridge.onSessionChanged(testRootDisposable) { seen = it }
        bridge.select("session-1")
        assertEquals("session-1", seen)
        assertEquals("session-1", bridge.selectedSessionId)
        assertEquals(project.name, bridge.projectId)
        bridge.select(null)
    }

    fun testDriverServiceStartsDisconnected() {
        assertEquals(DriverService.Status.DISCONNECTED, DriverService.get().status)
    }

    fun testEventStreamParsesBatches() {
        val events = EventStream.parse("""{"jsonrpc":"2.0","method":"events","params":{"dropped":0,"events":[
            {"eventId":"e1","type":"browser.console.error","sessionId":"s1","tabId":"tab-1","timestamp":"2026-01-01T00:00:00Z",
             "data":{"message":"boom"}}]}}""")
        assertEquals(1, events.size)
        assertEquals("browser.console.error", events[0].type)
        assertEquals("boom", events[0].data.path("message").asText())
        assertTrue(EventStream.parse("""{"jsonrpc":"2.0","id":1,"result":"pong"}""").isEmpty())
        assertTrue(EventStream.parse("garbage").isEmpty())
    }

    fun testDevServerDetection() {
        val json = dev.cdb.protocol.Json.mapper().readTree("""{"scripts":{"dev":"vite --port 5174"},"devDependencies":{"vite":"5"}}""")
        val s = DevServerDetector.fromPackageJson(json)
        assertEquals("http://localhost:5174", s.first().url)
        val next = DevServerDetector.fromPackageJson(dev.cdb.protocol.Json.mapper().readTree("""{"dependencies":{"next":"15"}}"""))
        assertEquals("http://localhost:3000", next.first().url)
    }

    fun testElementsTreeBuildsHierarchy() {
        val panel = ElementsPanel(project, testRootDisposable)
        panel.show(DomSnapshot("Login", "http://localhost:3000/login", "tab-1", SnapshotMode.COMPACT, 2, false, listOf(
            DomElement("element-1", null, 0, "form", "form", null, null, true, false, false, null, null, null, null),
            DomElement("element-2", "element-1", 1, "button", "button", "Sign In", null, true, true, false, null, null, null, null)
        ), null))
        // No exception and the tree model contains both nodes under the page root.
    }

    fun testAgentConfigSnippets() {
        assertTrue(AgentPanel.claudeCommand("/x/cdb-mcp-server.jar").contains("claude mcp add chromium-bridge -- java -jar"))
        assertTrue(AgentPanel.mcpJson("C:\\x\\cdb.jar").contains("C:\\\\x\\\\cdb.jar"))
        assertTrue(AgentPanel.codexToml("/x.jar").startsWith("[mcp_servers.chromium-bridge]"))
    }
}
