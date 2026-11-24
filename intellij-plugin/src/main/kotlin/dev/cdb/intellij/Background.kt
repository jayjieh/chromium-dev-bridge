package dev.cdb.intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import dev.cdb.client.DriverApiException

/**
 * Runs [work] on a pooled thread (never the EDT) and delivers the result or error on the EDT.
 * All driver calls in the plugin go through this helper.
 */
fun <T> background(work: () -> T, onError: (Throwable) -> Unit = {}, onSuccess: (T) -> Unit = {}) {
    val app = ApplicationManager.getApplication()
    app.executeOnPooledThread {
        try {
            val result = work()
            app.invokeLater({ onSuccess(result) }, ModalityState.any())
        } catch (t: Throwable) {
            app.invokeLater({ onError(t) }, ModalityState.any())
        }
    }
}

fun describe(t: Throwable): String = when (t) {
    is DriverApiException -> "${t.code()}: ${t.message}"
    else -> t.message ?: t.javaClass.simpleName
}

fun notify(project: Project?, message: String, type: NotificationType = NotificationType.INFORMATION) {
    NotificationGroupManager.getInstance().getNotificationGroup("Chromium Developer Bridge")
        .createNotification(message, type).notify(project)
}
