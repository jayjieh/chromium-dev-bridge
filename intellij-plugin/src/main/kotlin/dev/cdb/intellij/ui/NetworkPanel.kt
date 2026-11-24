package dev.cdb.intellij.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import dev.cdb.intellij.DriverService
import dev.cdb.intellij.ProjectBridge
import dev.cdb.intellij.background
import dev.cdb.protocol.Json
import dev.cdb.protocol.NetworkEntry
import dev.cdb.protocol.NetworkQuery
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.time.Instant
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

/** Network tab: request table with filters, details pane and a waterfall column. */
class NetworkPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val bridge = ProjectBridge.get(project)
    private val model = Model()
    private val table = JBTable(model)
    private val details = JBTextArea().apply { isEditable = false }
    private val failedOnly = JBCheckBox("Failed")
    private val xhrOnly = JBCheckBox("Fetch/XHR")
    private val url = JBTextField().apply { emptyText.text = "URL filter"; columns = 14 }

    inner class Model : AbstractTableModel() {
        val rows = ArrayList<NetworkEntry>()
        private val index = HashMap<String, Int>()
        private val columns = arrayOf("Method", "URL", "Status", "Type", "Time", "Waterfall")

        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(c: Int) = columns[c]
        override fun getValueAt(r: Int, c: Int): Any? {
            val e = rows[r]
            return when (c) {
                0 -> e.method()
                1 -> e.url()
                2 -> e.status()?.toString() ?: (if (e.state().name == "FAILED") (e.failureText() ?: "failed") else "…") +
                    (if (e.corsError()) " CORS" else "")
                3 -> e.resourceType()
                4 -> e.durationMs()?.let { "${it.toLong()} ms" } ?: ""
                else -> e
            }
        }

        fun upsert(e: NetworkEntry) {
            val i = index[e.requestId()]
            if (i != null) {
                rows[i] = e
                fireTableRowsUpdated(i, i)
            } else {
                index[e.requestId()] = rows.size
                rows.add(e)
                fireTableRowsInserted(rows.size - 1, rows.size - 1)
            }
        }

        fun clear() {
            rows.clear()
            index.clear()
            fireTableDataChanged()
        }

        fun timeRange(): Pair<Instant, Long> {
            val start = rows.minOfOrNull { it.startedAt() } ?: Instant.now()
            val end = rows.maxOfOrNull { it.startedAt().toEpochMilli() + (it.durationMs()?.toLong() ?: 0) } ?: start.toEpochMilli()
            return start to (end - start.toEpochMilli()).coerceAtLeast(1)
        }
    }

    /** Draws each request as a bar positioned by start time and sized by duration. */
    private inner class WaterfallRenderer : JPanel(), TableCellRenderer {
        private var entry: NetworkEntry? = null

        override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int): Component {
            entry = v as? NetworkEntry
            background = if (sel) t.selectionBackground else t.background
            return this
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val e = entry ?: return
            val (start, span) = model.timeRange()
            val x = ((e.startedAt().toEpochMilli() - start.toEpochMilli()).toDouble() / span * (width - 4)).toInt() + 2
            val w = (((e.durationMs() ?: 1.0) / span) * (width - 4)).toInt().coerceAtLeast(2)
            g.color = when {
                e.isFailure -> JBColor.RED
                e.state().name == "PENDING" -> JBColor.GRAY
                else -> JBColor.BLUE
            }
            g.fillRect(x, height / 4, w, height / 2)
        }
    }

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        toolbar.add(failedOnly)
        toolbar.add(xhrOnly)
        toolbar.add(url)
        toolbar.add(JButton("Reload").apply { addActionListener { reload() } })
        toolbar.add(JButton("Clear").also { b -> b.addActionListener { this@NetworkPanel.model.clear() } })
        add(toolbar, BorderLayout.NORTH)
        table.columnModel.getColumn(5).cellRenderer = WaterfallRenderer()
        table.columnModel.getColumn(1).preferredWidth = 320
        val split = JBSplitter(true, 0.65f)
        split.firstComponent = JBScrollPane(table)
        split.secondComponent = JBScrollPane(details)
        add(split, BorderLayout.CENTER)
        table.selectionModel.addListSelectionListener {
            val row = table.selectedRow
            if (row >= 0) details.text = describe(model.rows[table.convertRowIndexToModel(row)])
        }
        failedOnly.addActionListener { reload() }
        xhrOnly.addActionListener { reload() }
        url.addActionListener { reload() }
        bridge.onSessionChanged(parent) { reload() }
        bridge.onEvents(parent) { events ->
            val entries = events.filter { it.type.startsWith("browser.network.") }
                .mapNotNull { runCatching { Json.mapper().treeToValue(it.data, NetworkEntry::class.java) }.getOrNull() }
                .filter { accepts(it) }
            if (entries.isNotEmpty()) SwingUtilities.invokeLater { entries.forEach { model.upsert(it) } }
        }
    }

    private fun accepts(e: NetworkEntry): Boolean {
        if (failedOnly.isSelected && !e.isFailure) return false
        if (xhrOnly.isSelected && e.resourceType() != "fetch" && e.resourceType() != "xhr") return false
        val u = url.text.trim()
        return u.isEmpty() || e.url().contains(u)
    }

    fun reload() {
        val id = bridge.selectedSessionId ?: return
        val q = NetworkQuery(null, xhrOnly.isSelected.takeIf { it }, failedOnly.isSelected.takeIf { it }, null, null,
            url.text.trim().ifBlank { null }, null, null, 1000)
        background({ DriverService.get().client().network(id, q) }) { page ->
            model.clear()
            page.items().forEach { model.upsert(it) }
        }
    }

    private fun describe(e: NetworkEntry) = buildString {
        appendLine("${e.method()} ${e.url()}")
        appendLine("Status: ${e.status() ?: e.state()} ${e.statusText() ?: ""}  Type: ${e.resourceType()}  Time: ${e.durationMs() ?: "-"} ms")
        e.failureText()?.let { appendLine("Failure: $it") }
        e.redirectedFrom()?.let { appendLine("Redirected from: $it") }
        if (e.corsError()) appendLine("CORS error reported by the browser")
        appendLine("\nRequest headers (redacted):")
        e.requestHeaders()?.forEach { (k, v) -> appendLine("  $k: $v") }
        appendLine("\nResponse headers (redacted):")
        e.responseHeaders()?.forEach { (k, v) -> appendLine("  $k: $v") }
        e.requestBody()?.let { appendLine("\nRequest body:\n$it") }
        e.responseBody()?.let { appendLine("\nResponse body:\n$it") }
    }
}
