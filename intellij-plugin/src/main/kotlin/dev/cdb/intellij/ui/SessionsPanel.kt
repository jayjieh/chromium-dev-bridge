package dev.cdb.intellij.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import dev.cdb.intellij.DriverService
import dev.cdb.intellij.ProjectBridge
import dev.cdb.intellij.background
import dev.cdb.intellij.describe
import dev.cdb.intellij.notify
import dev.cdb.protocol.ApprovalKind
import dev.cdb.protocol.ApprovalRequest
import dev.cdb.protocol.SessionInfo
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

/**
 * Sessions tab: lifecycle management (create, select, restart, close) and the human approvals that agents cannot grant
 * themselves (non-development origins, sensitive actions).
 */
class SessionsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val bridge = ProjectBridge.get(project)
    private val model = object : DefaultTableModel(arrayOf("Session", "State", "Browser", "Owner", "Project", "URL", "Agents"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(model)
    private var sessions: List<SessionInfo> = emptyList()

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        toolbar.add(JButton("New").apply { addActionListener { create() } })
        toolbar.add(JButton("Attach to Chrome…").apply {
            toolTipText = "Attach to a Chrome started with --remote-debugging-port (scripts/<os>/start-chrome-debug)"
            addActionListener { attachChrome() }
        })
        toolbar.add(JButton("Inspect").apply { addActionListener { selected()?.let { bridge.select(it.sessionId()) } } })
        toolbar.add(JButton("Restart").apply { addActionListener { act { id -> DriverService.get().client().restartSession(id) } } })
        toolbar.add(JButton("Close").apply { addActionListener { act { id -> DriverService.get().client().closeSession(id) } } })
        toolbar.add(JButton("Approve origin…").apply { addActionListener { approveOrigin() } })
        toolbar.add(JButton("Allow sensitive actions").apply { addActionListener { approveSensitive() } })
        toolbar.add(JButton("Refresh").apply { addActionListener { reload() } })
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) selected()?.let { bridge.select(it.sessionId()) }
            }
        })
        DriverService.get().addStatusListener(parent) { s, _ -> if (s == DriverService.Status.CONNECTED) reload() }
        bridge.onEvents(parent) { events -> if (events.any { it.type.startsWith("browser.session.") }) SwingUtilities.invokeLater { reload() } }
        bridge.onSessionChanged(parent) { reload() }
        if (DriverService.get().status == DriverService.Status.CONNECTED) reload()
    }

    private fun selected(): SessionInfo? = table.selectedRow.takeIf { it >= 0 }?.let { sessions.getOrNull(table.convertRowIndexToModel(it)) }

    fun reload() {
        background({ DriverService.get().client().listSessions() }) { list ->
            sessions = list
            model.rowCount = 0
            list.forEach {
                model.addRow(arrayOf(
                    (if (it.sessionId() == bridge.selectedSessionId) "▶ " else "") + it.sessionId(), it.state(),
                    it.provider()?.name() ?: "", it.owner(),
                    it.projectId() ?: "", it.currentUrl() ?: "", if (it.agentAccess() || it.owner().startsWith("agent:")) "yes" else "no"
                ))
            }
            if (bridge.selectedSessionId == null) list.firstOrNull { it.projectId() == bridge.projectId && it.state().name == "RUNNING" }?.let { bridge.select(it.sessionId()) }
        }
    }

    private fun create() {
        background({ bridge.createSession(null) }, { notify(project, describe(it), NotificationType.ERROR) }) {
            bridge.select(it.sessionId())
            reload()
        }
    }

    private fun attachChrome() {
        val endpoint = Messages.showInputDialog(project,
            "Remote debugging endpoint of a Chrome you started with a dedicated profile " +
                "(scripts/<os>/start-chrome-debug). Only loopback addresses are accepted.",
            "Attach to Chrome", null, "http://127.0.0.1:9222", null) ?: return
        background({
            DriverService.get().client().createSession(dev.cdb.protocol.SessionCreateRequest.builder().projectId(bridge.projectId)
                .label("Attached Chrome").connectEndpoint(endpoint.trim())
                .agentAccess(dev.cdb.intellij.CdbSettings.get().state.shareSessionsWithAgents).build())
        }, { notify(project, describe(it), NotificationType.ERROR) }) {
            bridge.attachEvents()
            bridge.select(it.sessionId())
            reload()
        }
    }

    private fun act(action: (String) -> Any?) {
        val s = selected() ?: return
        background({ action(s.sessionId()) }, { notify(project, describe(it), NotificationType.WARNING) }) {
            if (s.sessionId() == bridge.selectedSessionId && s.state().name == "STOPPED") bridge.select(null)
            reload()
        }
    }

    private fun approveOrigin() {
        val s = selected() ?: return
        val origin = Messages.showInputDialog(project,
            "Allow session ${s.sessionId()} to load this non-development origin (e.g. https://staging.example.com).\n" +
                "Only approve sites you trust: AI agents using this session will be able to read and interact with them.",
            "Approve Origin", Messages.getWarningIcon()) ?: return
        background({ DriverService.get().client().approve(s.sessionId(), ApprovalRequest(ApprovalKind.ORIGIN, origin.trim(), "IDE user")) },
            { notify(project, describe(it), NotificationType.ERROR) }) { notify(project, "Approved ${it.value()} for ${s.sessionId()}") }
    }

    private fun approveSensitive() {
        val s = selected() ?: return
        val answer = Messages.showYesNoDialog(project,
            "Allow clicks, typing and form submission on approved non-development origins in ${s.sessionId()}?\n" +
                "These actions can have real-world consequences.", "Allow Sensitive Actions", Messages.getWarningIcon())
        if (answer != Messages.YES) return
        background({ DriverService.get().client().approve(s.sessionId(), ApprovalRequest(ApprovalKind.SENSITIVE_ACTIONS, null, "IDE user")) },
            { notify(project, describe(it), NotificationType.ERROR) })
    }
}
