package dev.cdb.intellij.ui

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.cdb.intellij.CdbSettings
import dev.cdb.intellij.DevServerDetector
import dev.cdb.intellij.DriverService
import dev.cdb.intellij.ProjectBridge
import dev.cdb.intellij.background
import dev.cdb.intellij.describe
import dev.cdb.intellij.notify
import dev.cdb.protocol.ActionRequest
import dev.cdb.protocol.ActionType
import dev.cdb.protocol.NavigateRequest
import dev.cdb.protocol.ScreenshotRequest
import dev.cdb.protocol.ViewportPreset
import dev.cdb.protocol.ViewportRequest
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Browser tab: address bar, navigation controls, viewport and session selector, and a live preview of the
 * Playwright-managed Chromium session (see ADR-0001: the inspected browser is the driver's Chromium, not JCEF).
 */
class BrowserPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val bridge = ProjectBridge.get(project)
    private val driver = DriverService.get()
    private val address = JBTextField(CdbSettings.get().state.defaultUrl)
    private val status = JBLabel("Disconnected")
    private val sessionLabel = JBLabel("No session")
    private val viewport = ComboBox(arrayOf("Viewport…") + ViewportPreset.entries.map { it.name })
    private val preview = PreviewCanvas()
    private val timer = Timer(CdbSettings.get().state.previewIntervalMs) { refreshPreview() }
    @Volatile private var fetching = false

    init {
        val nav = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        nav.add(button("◀", "Back") { withSession { id -> driver.client().back(id, null) } })
        nav.add(button("▶", "Forward") { withSession { id -> driver.client().forward(id, null) } })
        nav.add(button("⟳", "Refresh") { withSession { id -> driver.client().refresh(id, null) } })
        nav.add(button("✕", "Stop") { withSession { id -> driver.client().stop(id, null) } })
        nav.add(button("+", "Open tab") { withSession { id -> driver.client().openTab(id, address.text.trim().ifBlank { null }) } })
        nav.add(button("−", "Close tab") {
            withSession { id ->
                val c = driver.client()
                val tabs = c.tabs(id)
                if (tabs.size > 1) c.closeTab(id, tabs.first { it.active }.tabId())
            }
        })
        nav.add(button("📷", "Screenshot") {
            withSession { id -> driver.client().screenshot(id, ScreenshotRequest.viewport("ide")) }
        })
        nav.add(button("⇱", "Open in external browser") { BrowserUtil.browse(address.text.trim()) })
        nav.add(viewport)
        nav.add(button("New session", "Create a session for this project") { newSession() })
        viewport.addActionListener {
            val selected = viewport.selectedItem as String
            if (selected != "Viewport…") withSession { id -> driver.client().setViewport(id, ViewportRequest(ViewportPreset.valueOf(selected), null, null)) }
        }
        address.addActionListener { go() }
        val suggestions = DevServerDetector.detect(project.basePath?.let { Path.of(it) })
        if (suggestions.isNotEmpty() && address.text == "http://localhost:3000") address.text = suggestions.first().url
        val top = JPanel(BorderLayout())
        top.add(address, BorderLayout.CENTER)
        top.add(button("Go", "Navigate") { go() }, BorderLayout.EAST)
        val header = JPanel(BorderLayout())
        header.add(top, BorderLayout.NORTH)
        header.add(nav, BorderLayout.CENTER)
        val footer = JPanel(BorderLayout())
        footer.border = JBUI.Borders.empty(2, 6)
        footer.add(sessionLabel, BorderLayout.WEST)
        footer.add(status, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)
        add(preview, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)

        driver.addStatusListener(parent) { s, detail -> updateStatus(s, detail) }
        updateStatus(driver.status, null)
        bridge.onSessionChanged(parent) { id -> sessionLabel.text = if (id == null) "No session" else "Inspecting $id" }
        bridge.onEvents(parent) { events ->
            events.lastOrNull { it.type == "browser.navigation.completed" }?.let { e ->
                val url = e.data.path("url").asText()
                javax.swing.SwingUtilities.invokeLater { if (!address.hasFocus()) address.text = url }
            }
        }
        timer.start()
        Disposer.register(parent) { timer.stop() }
    }

    private fun updateStatus(s: DriverService.Status, detail: String?) {
        status.text = when (s) {
            DriverService.Status.CONNECTED -> "● Connected"
            DriverService.Status.CONNECTING -> "○ Connecting…"
            DriverService.Status.DISCONNECTED -> "○ Disconnected" + (detail?.let { " ($it)" } ?: "")
        }
        status.foreground = if (s == DriverService.Status.CONNECTED) JBColor.GREEN.darker() else JBColor.GRAY
        status.toolTipText = detail
    }

    private fun go() {
        val url = address.text.trim()
        if (url.isEmpty()) return
        background({
            val id = bridge.selectedSessionId ?: bridge.createSession(null).sessionId()
            driver.client().navigate(id, NavigateRequest(url))
            id
        }, { notify(project, describe(it), NotificationType.WARNING) }) { id -> if (bridge.selectedSessionId != id) bridge.select(id) }
    }

    private fun newSession() {
        background({ bridge.createSession(address.text.trim()) }, { notify(project, describe(it), NotificationType.ERROR) }) {
            bridge.select(it.sessionId())
        }
    }

    private fun withSession(action: (String) -> Any?) {
        val id = bridge.selectedSessionId
        if (id == null) {
            notify(project, "Select or create a browser session first", NotificationType.INFORMATION)
            return
        }
        background({ action(id) }, { notify(project, describe(it), NotificationType.WARNING) })
    }

    private fun refreshPreview() {
        val id = bridge.selectedSessionId ?: return
        if (fetching || !isShowing || driver.status != DriverService.Status.CONNECTED) return
        fetching = true
        background({ driver.client().preview(id, null, 60) }, { fetching = false }) { bytes ->
            fetching = false
            preview.image = ImageIO.read(ByteArrayInputStream(bytes))
            preview.repaint()
        }
    }

    private fun button(text: String, tooltip: String, action: () -> Unit) = JButton(text).apply {
        toolTipText = tooltip
        isFocusable = false
        addActionListener { action() }
    }

    /** Scaled preview; clicks are forwarded to the browser as coordinate clicks (best effort). */
    private inner class PreviewCanvas : JPanel() {
        var image: Image? = null
        private var scale = 1.0
        private var offsetX = 0
        private var offsetY = 0

        init {
            background = JBColor.background()
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val img = image ?: return
                    val x = (e.x - offsetX) / scale
                    val y = (e.y - offsetY) / scale
                    if (x < 0 || y < 0 || x > img.getWidth(null) || y > img.getHeight(null)) return
                    withSession { id ->
                        driver.client().perform(id, ActionRequest(ActionType.CLICK_AT, null, null, null, null, null, null, null,
                            null, null, x, y, null))
                    }
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = image
            if (img == null) {
                g.color = JBColor.GRAY
                g.drawString(if (bridge.selectedSessionId == null) "Create a session to see the browser preview" else "Loading preview…", 12, 24)
                return
            }
            val w = img.getWidth(null).toDouble()
            val h = img.getHeight(null).toDouble()
            scale = minOf(width / w, height / h, 1.0)
            val dw = (w * scale).toInt()
            val dh = (h * scale).toInt()
            offsetX = (width - dw) / 2
            offsetY = 0
            g.drawImage(img, offsetX, offsetY, dw, dh, null)
        }
    }
}
