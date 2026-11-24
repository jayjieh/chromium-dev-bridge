package dev.cdb.intellij

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** Settings | Tools | Chromium Developer Bridge. */
class CdbConfigurable : Configurable {
    private val jar = TextFieldWithBrowseButton()
    private val connection = JBTextField()
    private val autoStart = JBCheckBox("Start the driver automatically when the tool window opens")
    private val headed = JBCheckBox("Create headed (visible) browser sessions by default")
    private val share = JBCheckBox("Let AI agents (MCP) use sessions created in the IDE")
    private val url = JBTextField()
    private val interval = JSpinner(SpinnerNumberModel(1000, 250, 10000, 250))
    private var panel: JPanel? = null

    override fun getDisplayName() = "Chromium Developer Bridge"

    override fun createComponent(): JComponent {
        jar.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor("jar"))
        connection.emptyText.text = DriverService.defaultConnectionFile().toString()
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Driver jar (cdb-driver-exec.jar):", jar)
            .addLabeledComponent("Connection file:", connection)
            .addComponent(autoStart)
            .addComponent(headed)
            .addComponent(share)
            .addLabeledComponent("Default URL:", url)
            .addLabeledComponent("Preview refresh (ms):", interval)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = CdbSettings.get().state
        return jar.text != s.driverJarPath || connection.text != s.connectionFile || autoStart.isSelected != s.autoStartDriver ||
            headed.isSelected != s.headedByDefault || share.isSelected != s.shareSessionsWithAgents || url.text != s.defaultUrl ||
            interval.value != s.previewIntervalMs
    }

    override fun apply() {
        val s = CdbSettings.get().state
        s.driverJarPath = jar.text.trim()
        s.connectionFile = connection.text.trim()
        s.autoStartDriver = autoStart.isSelected
        s.headedByDefault = headed.isSelected
        s.shareSessionsWithAgents = share.isSelected
        s.defaultUrl = url.text.trim()
        s.previewIntervalMs = interval.value as Int
    }

    override fun reset() {
        val s = CdbSettings.get().state
        jar.text = s.driverJarPath
        connection.text = s.connectionFile
        autoStart.isSelected = s.autoStartDriver
        headed.isSelected = s.headedByDefault
        share.isSelected = s.shareSessionsWithAgents
        url.text = s.defaultUrl
        interval.value = s.previewIntervalMs
    }

    override fun disposeUIResources() {
        panel = null
    }
}
