package dev.cdb.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Application-level plugin settings. Contains no secrets: tokens are read from the driver's connection file. */
@State(name = "ChromiumDeveloperBridge", storages = [Storage("chromium-dev-bridge.xml")])
class CdbSettings : PersistentStateComponent<CdbSettings.State> {
    data class State(
        var driverJarPath: String = "",
        var connectionFile: String = "",
        var autoStartDriver: Boolean = true,
        var headedByDefault: Boolean = false,
        var previewIntervalMs: Int = 1000,
        var defaultUrl: String = "http://localhost:3000",
        var shareSessionsWithAgents: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(s: State) {
        state = s
    }

    companion object {
        fun get(): CdbSettings = ApplicationManager.getApplication().getService(CdbSettings::class.java)
    }
}
