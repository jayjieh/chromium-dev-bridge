package dev.cdb.intellij.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import dev.cdb.intellij.CdbSettings
import dev.cdb.intellij.DriverService
import dev.cdb.intellij.ProjectBridge
import dev.cdb.intellij.background
import dev.cdb.intellij.describe
import dev.cdb.intellij.notify
import dev.cdb.protocol.NavigateRequest

/** Tools | Open URL in Chromium Bridge: navigates the selected session (creating one if needed). */
class OpenUrlAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val url = Messages.showInputDialog(project, "URL to open:", "Chromium Developer Bridge", null,
            CdbSettings.get().state.defaultUrl, null) ?: return
        val bridge = ProjectBridge.get(project)
        background({
            val id = bridge.selectedSessionId ?: bridge.createSession(null).sessionId()
            DriverService.get().client().navigate(id, NavigateRequest(url))
            id
        }, { notify(project, describe(it), NotificationType.ERROR) }) { id -> bridge.select(id) }
    }
}
