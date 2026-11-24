package dev.cdb.intellij.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.ui.table.JBTable
import dev.cdb.intellij.DriverService
import dev.cdb.intellij.ProjectBridge
import dev.cdb.intellij.background
import dev.cdb.intellij.describe
import dev.cdb.intellij.notify
import dev.cdb.protocol.DomElement
import dev.cdb.protocol.DomSnapshot
import dev.cdb.protocol.ElementDetails
import dev.cdb.protocol.InspectRequest
import dev.cdb.protocol.Locator
import dev.cdb.protocol.SnapshotMode
import dev.cdb.protocol.SnapshotRequest
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.table.DefaultTableModel

/** Elements tab: collapsible DOM tree (from a snapshot) and a property inspector for the selected element. */
class ElementsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val bridge = ProjectBridge.get(project)
    private val mode = JComboBox(SnapshotMode.entries.filter { it != SnapshotMode.ACCESSIBILITY }.toTypedArray())
    private val filter = JBTextField().apply { emptyText.text = "Filter by name or text" }
    private val root = DefaultMutableTreeNode("Page")
    private val tree = Tree(DefaultTreeModel(root))
    private val props = DefaultTableModel(arrayOf("Property", "Value"), 0)
    @Volatile private var stale = false

    data class Node(val element: DomElement) {
        override fun toString(): String = buildString {
            append('<').append(element.tag()).append('>')
            element.role()?.let { append(" [").append(it).append(']') }
            element.name()?.let { append(" \"").append(it).append('"') }
            element.text()?.takeIf { it != element.name() }?.let { append(" — ").append(it.take(60)) }
            if (!element.visible()) append(" (hidden)")
            append("  ").append(element.reference())
        }
    }

    init {
        mode.selectedItem = SnapshotMode.COMPACT
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT))
        toolbar.add(mode)
        toolbar.add(filter)
        filter.columns = 18
        toolbar.add(JButton("Refresh").also { b -> b.addActionListener { refresh() } })
        add(toolbar, BorderLayout.NORTH)
        val split = JBSplitter(true, 0.6f)
        split.firstComponent = JBScrollPane(tree)
        split.secondComponent = JBScrollPane(JBTable(props))
        add(split, BorderLayout.CENTER)
        tree.addTreeSelectionListener {
            val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Node ?: return@addTreeSelectionListener
            inspect(node.element.reference())
        }
        bridge.onSessionChanged(parent) { refresh(quiet = true) }
        // After a navigation the tree is stale: refresh now if visible, otherwise when the tab is shown again.
        val navRefresh = javax.swing.Timer(500) { if (isShowing) { stale = false; refresh(quiet = true) } }.apply { isRepeats = false }
        bridge.onEvents(parent) { events ->
            if (events.any { it.type == "browser.navigation.completed" }) javax.swing.SwingUtilities.invokeLater {
                stale = true
                navRefresh.restart()
            }
        }
        addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(event: javax.swing.event.AncestorEvent) {
                if (stale) { stale = false; refresh(quiet = true) }
            }
            override fun ancestorRemoved(event: javax.swing.event.AncestorEvent) {}
            override fun ancestorMoved(event: javax.swing.event.AncestorEvent) {}
        })
    }

    fun refresh(quiet: Boolean = false) {
        val id = bridge.selectedSessionId ?: return
        val req = SnapshotRequest(null, mode.selectedItem as SnapshotMode, null, 2000, null, null, filter.text.trim().ifBlank { null })
        background({ DriverService.get().client().dom(id, req) }, { if (!quiet) notify(project, describe(it), NotificationType.WARNING) }) { show(it) }
    }

    internal fun show(snapshot: DomSnapshot) {
        root.removeAllChildren()
        root.userObject = "${snapshot.pageTitle()} — ${snapshot.url()}" + if (snapshot.truncated()) " (truncated)" else ""
        val byRef = HashMap<String, DefaultMutableTreeNode>()
        for (e in snapshot.elements()) {
            val node = DefaultMutableTreeNode(Node(e))
            byRef[e.reference()] = node
            (e.parent()?.let { byRef[it] } ?: root).add(node)
        }
        (tree.model as DefaultTreeModel).reload()
        var row = 0
        while (row < tree.rowCount && row < 300) tree.expandRow(row++)
    }

    private fun inspect(ref: String) {
        val id = bridge.selectedSessionId ?: return
        background({ DriverService.get().client().inspect(id, InspectRequest(null, Locator.ref(ref), null)) },
            { notify(project, describe(it), NotificationType.WARNING) }) { showDetails(it) }
    }

    private fun showDetails(d: ElementDetails) {
        props.rowCount = 0
        fun row(k: String, v: Any?) { if (v != null) props.addRow(arrayOf(k, v.toString())) }
        row("reference", d.reference()); row("tag", d.tag()); row("role", d.role()); row("name", d.name())
        row("visible", d.visible()); row("enabled", d.enabled()); row("editable", d.editable()); row("checked", d.checked())
        d.bounds()?.let { row("bounds", "x=${it.x()} y=${it.y()} ${it.width()}×${it.height()}") }
        d.formField()?.let { f -> row("field.type", f.type()); row("field.required", f.required()); row("field.valid", f.valid())
            row("field.validationMessage", f.validationMessage()); row("field.value", f.value()) }
        d.attributes()?.forEach { (k, v) -> row("@$k", v) }
        d.computedStyles()?.forEach { (k, v) -> row("css:$k", v) }
        row("ancestors", d.ancestors()?.joinToString(" < "))
    }
}
