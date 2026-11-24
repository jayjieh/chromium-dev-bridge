package dev.cdb.intellij.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import dev.cdb.intellij.CdbSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JPanel

/**
 * AI Agent tab: ready-to-use MCP client configuration for Claude Code and Codex. The agent drives the browser through
 * the MCP server; it uses the agent token, so it can't grant itself approvals.
 */
class AgentPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val text = JBTextArea().apply { isEditable = false; lineWrap = false }

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        toolbar.add(JButton("Copy Copilot (IntelliJ) mcp.json").apply { addActionListener { copy(copilotJson(mcpJar())) } })
        toolbar.add(JButton("Copy Claude Code command").apply { addActionListener { copy(claudeCommand(mcpJar())) } })
        toolbar.add(JButton("Copy .mcp.json").apply { addActionListener { copy(mcpJson(mcpJar())) } })
        toolbar.add(JButton("Copy Codex config").apply { addActionListener { copy(codexToml(mcpJar())) } })
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(text), BorderLayout.CENTER)
        text.text = render()
    }

    private fun mcpJar(): String {
        dev.cdb.intellij.InstallInfo.read()?.mcpJar?.let { return it.replace('\\', '/') }
        val driverJar = CdbSettings.get().state.driverJarPath
        return if (driverJar.isNotBlank()) Path.of(driverJar).resolveSibling("cdb-mcp-server.jar").toString().replace('\\', '/')
        else "/path/to/chromium-dev-bridge/lib/cdb-mcp-server.jar"
    }

    private fun render() = """
        |Connect an AI coding agent to the browser session used by "${project.name}".
        |
        |GitHub Copilot in IntelliJ (Agent mode > tools icon > Add MCP Tools > paste into mcp.json):
        |${copilotJson(mcpJar())}
        |
        |GitHub Copilot CLI:
        |  copilot mcp add chromium-bridge --env CDB_CLIENT_NAME=copilot -- java -jar "${mcpJar()}"
        |
        |Claude Code (run in the project directory):
        |  ${claudeCommand(mcpJar())}
        |
        |or commit this as .mcp.json:
        |${mcpJson(mcpJar())}
        |
        |Codex (~/.codex/config.toml):
        |${codexToml(mcpJar())}
        |
        |Then ask the agent, for example:
        |  "Open http://localhost:3000/login in the browser bridge, check the console and failed network
        |   requests, find the cause in the code, fix it and verify the login flow with browser_test_flow."
        |
        |Security: the agent can only reach development hosts. Approve other origins or sensitive actions in the
        |Sessions tab. Page content is returned to the agent as untrusted data.
    """.trimMargin()

    private fun copy(s: String) = CopyPasteManager.getInstance().setContents(StringSelection(s))

    companion object {
        fun claudeCommand(jar: String) = "claude mcp add chromium-bridge -- java -jar \"$jar\""
        fun mcpJson(jar: String) = """
            |{
            |  "mcpServers": {
            |    "chromium-bridge": {
            |      "command": "java",
            |      "args": ["-jar", "${jar.replace("\\", "\\\\")}"]
            |    }
            |  }
            |}
        """.trimMargin()
        fun copilotJson(jar: String) = """
            |{
            |  "servers": {
            |    "chromium-bridge": {
            |      "command": "java",
            |      "args": ["-jar", "${jar.replace("\\", "/")}"]
            |    }
            |  }
            |}
        """.trimMargin()
        fun codexToml(jar: String) = """
            |[mcp_servers.chromium-bridge]
            |command = "java"
            |args = ["-jar", "${jar.replace("\\", "\\\\")}"]
        """.trimMargin()
    }
}
