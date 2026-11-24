package dev.cdb.intellij.ui

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import dev.cdb.intellij.CdbConfigurable
import dev.cdb.intellij.DriverService
import dev.cdb.intellij.ProjectBridge
import dev.cdb.intellij.background
import dev.cdb.intellij.describe
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/** Settings tab: connection status, connect/disconnect, and a link to the full settings page. */
class SettingsPanel(private val project: Project) : JPanel() {
    private val info = JBLabel()

    init {
        layout = com.intellij.ui.components.panels.VerticalLayout(4)
        val row = JPanel(FlowLayout(FlowLayout.LEFT))
        row.add(JButton("Connect / start driver").apply {
            addActionListener {
                info.text = "Connecting…"
                background({ DriverService.get().connect() }, { info.text = describe(it) }) {
                    ProjectBridge.get(project).attachEvents()
                    info.text = "Connected to ${it.baseUrl()}"
                }
            }
        })
        row.add(JButton("Disconnect").apply { addActionListener { DriverService.get().disconnect(); info.text = "Disconnected" } })
        row.add(JButton("Copy Chrome pairing code").apply {
            toolTipText = "Paste into the Chromium Developer Bridge Chrome extension to share tabs from your own Chrome"
            addActionListener {
                val code = dev.cdb.client.ConnectionFiles.read(DriverService.get().connectionFile()).orElse(null)?.extensionPairingCode()
                if (code == null) {
                    info.text = "Start the driver first (no pairing code available)"
                } else {
                    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(code))
                    info.text = "Pairing code copied. Paste it into the extension popup in Chrome."
                }
            }
        })
        row.add(JButton("Open settings…").apply {
            addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, CdbConfigurable::class.java) }
        })
        add(row)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(info) })
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Connection file: ${DriverService.get().connectionFile()}"))
        })
        info.text = "Status: ${DriverService.get().status}"
        DriverService.get().addStatusListener(project) { s, detail -> info.text = "Status: $s" + (detail?.let { " — $it" } ?: "") }
    }
}
