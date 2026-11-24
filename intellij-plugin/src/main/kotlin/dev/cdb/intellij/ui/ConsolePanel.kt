package dev.cdb.intellij.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import dev.cdb.intellij.DriverService
import dev.cdb.intellij.ProjectBridge
import dev.cdb.intellij.SourceNavigator
import dev.cdb.intellij.background
import dev.cdb.protocol.ConsoleEntry
import dev.cdb.protocol.ConsoleLevel
import dev.cdb.protocol.ConsoleQuery
import dev.cdb.protocol.Json
import dev.cdb.protocol.SourceLocation
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Console tab: real-time console messages, errors and unhandled rejections. Double-click an entry (or a stack frame)
 * to open the original source in the editor.
 */
class ConsolePanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val bridge = ProjectBridge.get(project)
    private val model = DefaultListModel<Any>()
    private val list = JBList(model)
    private val level = JComboBox<Any>(arrayOf<Any>("All levels", *ConsoleLevel.entries.toTypedArray()))
    private val search = JBTextField().apply { emptyText.text = "Search"; columns = 16 }
    private val maxEntries = 5000

    /** A stack frame line shown under its entry. */
    data class FrameRow(val label: String, val location: SourceLocation)

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        toolbar.add(level)
        toolbar.add(search)
        toolbar.add(JButton("Reload").apply { addActionListener { reload() } })
        toolbar.add(JButton("Clear").also { b -> b.addActionListener { this@ConsolePanel.model.clear() } })
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        list.cellRenderer = Renderer()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val loc = when (val v = list.selectedValue) {
                    is ConsoleEntry -> v.originalSource() ?: v.source()
                    is FrameRow -> v.location
                    else -> null
                } ?: return
                if (!SourceNavigator.open(project, loc)) {
                    dev.cdb.intellij.notify(project, "No project file matches ${loc.file() ?: loc.url()}")
                }
            }
        })
        level.addActionListener { reload() }
        search.addActionListener { reload() }
        bridge.onSessionChanged(parent) { reload() }
        bridge.onEvents(parent) { events ->
            val entries = events.filter { it.type == "browser.console.message" || it.type == "browser.console.error" }
                .mapNotNull { runCatching { Json.mapper().treeToValue(it.data, ConsoleEntry::class.java) }.getOrNull() }
                .filter { accepts(it) }
            if (entries.isNotEmpty()) SwingUtilities.invokeLater { entries.forEach { append(it) } }
        }
    }

    private fun accepts(e: ConsoleEntry): Boolean {
        val min = level.selectedItem as? ConsoleLevel
        if (min != null && e.level().ordinal < min.ordinal) return false
        val q = search.text.trim()
        return q.isEmpty() || e.message().contains(q, ignoreCase = true)
    }

    private fun append(e: ConsoleEntry) {
        model.addElement(e)
        e.stack()?.take(8)?.forEach { f ->
            val loc = f.original() ?: f.location()
            if (loc != null) model.addElement(FrameRow("    at ${f.function() ?: "<anonymous>"} (${loc.file() ?: loc.url()}:${loc.line()}:${loc.column()})", loc))
        }
        while (model.size() > maxEntries) model.remove(0)
        list.ensureIndexIsVisible(model.size() - 1)
    }

    fun reload() {
        val id = bridge.selectedSessionId ?: return
        val q = ConsoleQuery(null, level.selectedItem as? ConsoleLevel, null, search.text.trim().ifBlank { null }, null, null, 1000)
        background({ DriverService.get().client().console(id, q) }) { page ->
            model.clear()
            page.items().forEach { append(it) }
        }
    }

    private class Renderer : ColoredListCellRenderer<Any>() {
        override fun customizeCellRenderer(list: JList<out Any>, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean) {
            when (value) {
                is ConsoleEntry -> {
                    val attrs = when (value.level()) {
                        ConsoleLevel.ERROR -> SimpleTextAttributes.ERROR_ATTRIBUTES
                        ConsoleLevel.WARNING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, com.intellij.ui.JBColor.ORANGE)
                        ConsoleLevel.DEBUG -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    }
                    append("${value.timestamp().toString().substring(11, 23)} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (value.kind().name != "CONSOLE") append("[${value.kind()}] ", attrs)
                    append(value.message().lineSequence().first().take(500), attrs)
                    val loc = value.originalSource() ?: value.source()
                    if (loc != null) append("  ${loc.file() ?: loc.url()}:${loc.line()}", SimpleTextAttributes.LINK_ATTRIBUTES)
                }
                is FrameRow -> append(value.label, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                else -> append(value.toString())
            }
        }
    }
}
