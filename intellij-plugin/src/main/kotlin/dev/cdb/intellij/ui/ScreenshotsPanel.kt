package dev.cdb.intellij.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import dev.cdb.intellij.DriverService
import dev.cdb.intellij.ProjectBridge
import dev.cdb.intellij.background
import dev.cdb.intellij.describe
import dev.cdb.intellij.notify
import dev.cdb.protocol.CompareRequest
import dev.cdb.protocol.ScreenshotInfo
import dev.cdb.protocol.ScreenshotRequest
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** Screenshots tab: history, viewer, and pixel comparison of two selected screenshots. */
class ScreenshotsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val bridge = ProjectBridge.get(project)
    private val model = DefaultListModel<ScreenshotInfo>()
    private val list = JBList(model).apply { selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION }
    private val viewer = JLabel()
    private val result = JLabel(" ")

    init {
        list.installCellRenderer { s: ScreenshotInfo ->
            JLabel("${s.name() ?: s.screenshotId()}  ${s.width()}×${s.height()}  ${s.createdAt().toString().substring(11, 19)}")
        }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        toolbar.add(JButton("Capture").apply { addActionListener { capture(false) } })
        toolbar.add(JButton("Full page").apply { addActionListener { capture(true) } })
        toolbar.add(JButton("Compare selected").apply { addActionListener { compare() } })
        toolbar.add(JButton("Refresh").apply { addActionListener { reload() } })
        toolbar.add(result)
        add(toolbar, BorderLayout.NORTH)
        val split = JBSplitter(false, 0.3f)
        split.firstComponent = JBScrollPane(list)
        split.secondComponent = JBScrollPane(viewer)
        add(split, BorderLayout.CENTER)
        list.addListSelectionListener { if (!it.valueIsAdjusting) list.selectedValue?.let { s -> show(s.screenshotId()) } }
        bridge.onSessionChanged(parent) { reload() }
        bridge.onEvents(parent) { events -> if (events.any { it.type == "browser.screenshot.created" }) javax.swing.SwingUtilities.invokeLater { reload() } }
    }

    fun reload() {
        val id = bridge.selectedSessionId ?: return
        background({ DriverService.get().client().screenshots(id) }) { shots ->
            model.clear()
            shots.forEach { model.addElement(it) }
        }
    }

    private fun capture(fullPage: Boolean) {
        val id = bridge.selectedSessionId ?: return
        background({
            DriverService.get().client().screenshot(id, ScreenshotRequest(null, if (fullPage) "full-page" else "viewport", fullPage,
                null, null, null, null, true, false))
        }, { notify(project, describe(it), NotificationType.WARNING) }) { reload() }
    }

    private fun show(screenshotId: String) {
        background({ DriverService.get().client().screenshotImage(screenshotId) }) { bytes ->
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@background
            val maxW = (viewer.parent?.width ?: img.width).coerceAtLeast(100) - 8
            viewer.icon = ImageIcon(if (img.width > maxW) img.getScaledInstance(maxW, img.height * maxW / img.width, java.awt.Image.SCALE_SMOOTH) else img)
        }
    }

    private fun compare() {
        val selected = list.selectedValuesList
        if (selected.size != 2) {
            result.text = "Select exactly two screenshots"
            return
        }
        background({
            DriverService.get().client().compare(CompareRequest(selected[0].screenshotId(), selected[1].screenshotId(), null, null, null))
        }, { notify(project, describe(it), NotificationType.WARNING) }) { r ->
            result.text = (if (r.match()) "Match: " else "Different: ") + r.message()
            r.diffScreenshotId()?.let { show(it) }
        }
    }
}
