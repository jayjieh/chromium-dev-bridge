package dev.cdb.intellij.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.cdb.intellij.CdbSettings
import dev.cdb.intellij.DriverService
import dev.cdb.intellij.ProjectBridge
import dev.cdb.intellij.background

/** Creates the "Chromium Bridge" tool window with its tabs. */
class BridgeToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val disposable = toolWindow.disposable
        val tabs = listOf(
            "Browser" to BrowserPanel(project, disposable),
            "Elements" to ElementsPanel(project, disposable),
            "Console" to ConsolePanel(project, disposable),
            "Network" to NetworkPanel(project, disposable),
            "Screenshots" to ScreenshotsPanel(project, disposable),
            "AI Agent" to AgentPanel(project),
            "Sessions" to SessionsPanel(project, disposable),
            "Settings" to SettingsPanel(project),
        )
        for ((title, panel) in tabs) {
            toolWindow.contentManager.addContent(factory.createContent(panel, title, false))
        }
        if (CdbSettings.get().state.autoStartDriver && DriverService.get().status == DriverService.Status.DISCONNECTED) {
            background({ DriverService.get().connect() }) { ProjectBridge.get(project).attachEvents() }
        } else {
            ProjectBridge.get(project).attachEvents()
        }
        Disposer.register(disposable) { /* panels unregister their listeners via the same disposable */ }
    }

    override fun shouldBeAvailable(project: Project) = true
}
