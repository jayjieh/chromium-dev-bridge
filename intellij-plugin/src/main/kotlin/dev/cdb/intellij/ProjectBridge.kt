package dev.cdb.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.cdb.protocol.SessionCreateRequest
import dev.cdb.protocol.SessionInfo
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-project state: which browser session the tool window inspects, and event fan-out to the project's panels.
 * Disposed with the project, which unregisters listeners from the shared driver connection.
 */
@Service(Service.Level.PROJECT)
class ProjectBridge(val project: Project) : Disposable {
    private val sessionListeners = CopyOnWriteArrayList<(String?) -> Unit>()
    private val eventListeners = CopyOnWriteArrayList<(List<BridgeEvent>) -> Unit>()
    @Volatile private var unsubscribe: (() -> Unit)? = null

    @Volatile var selectedSessionId: String? = null
        private set

    val projectId: String get() = project.name

    fun select(sessionId: String?) {
        selectedSessionId = sessionId
        sessionListeners.forEach { it(sessionId) }
    }

    fun onSessionChanged(parent: Disposable, l: (String?) -> Unit) {
        sessionListeners.add(l)
        com.intellij.openapi.util.Disposer.register(parent) { sessionListeners.remove(l) }
    }

    /** Listener receives only events of the selected session (on a background thread). */
    fun onEvents(parent: Disposable, l: (List<BridgeEvent>) -> Unit) {
        eventListeners.add(l)
        com.intellij.openapi.util.Disposer.register(parent) { eventListeners.remove(l) }
    }

    /** Attaches to the current event stream; call after (re)connecting. */
    fun attachEvents() {
        unsubscribe?.invoke()
        unsubscribe = DriverService.get().events?.addListener { events ->
            val selected = selectedSessionId
            val mine = events.filter { it.sessionId == null || it.sessionId == selected || it.type.startsWith("browser.session.") }
            if (mine.isNotEmpty()) eventListeners.forEach { it(mine) }
        }
    }

    /** Creates a session associated with this project. Blocking: call off the EDT. */
    fun createSession(url: String?, headless: Boolean? = null): SessionInfo {
        val s = CdbSettings.get().state
        val info = DriverService.get().client().createSession(
            SessionCreateRequest.builder().projectId(projectId).label("IntelliJ: $projectId")
                .headless(headless ?: !s.headedByDefault).agentAccess(s.shareSessionsWithAgents)
                .initialUrl(url?.takeIf { it.isNotBlank() }).build()
        )
        attachEvents()
        return info
    }

    override fun dispose() {
        unsubscribe?.invoke()
        sessionListeners.clear()
        eventListeners.clear()
    }

    companion object {
        fun get(project: Project): ProjectBridge = project.getService(ProjectBridge::class.java)
    }
}
