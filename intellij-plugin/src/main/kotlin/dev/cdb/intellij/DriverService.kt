package dev.cdb.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import dev.cdb.client.BrowserDriverClient
import dev.cdb.client.DriverLauncher
import dev.cdb.protocol.ConnectionInfo
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Application-level connection to the browser driver (one driver serves all open projects).
 * Uses the admin token from the connection file; never stores tokens in IDE settings.
 */
@Service(Service.Level.APP)
class DriverService : Disposable {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    private val log = logger<DriverService>()
    private val statusListeners = CopyOnWriteArrayList<(Status, String?) -> Unit>()
    @Volatile private var info: ConnectionInfo? = null
    @Volatile private var client: BrowserDriverClient? = null
    @Volatile var status: Status = Status.DISCONNECTED
        private set
    @Volatile var events: EventStream? = null
        private set

    fun connectionFile(): Path {
        val configured = CdbSettings.get().state.connectionFile
        return if (configured.isNotBlank()) Path.of(configured) else defaultConnectionFile()
    }

    /** Returns the client, connecting (and optionally starting the driver) first. Blocking: call off the EDT. */
    fun client(): BrowserDriverClient {
        client?.let { if (status == Status.CONNECTED) return it }
        return connect()
    }

    @Synchronized
    fun connect(): BrowserDriverClient {
        client?.let { if (status == Status.CONNECTED && it.isUp) return it }
        check(!ApplicationManager.getApplication().isDispatchThread) { "Driver connection must not run on the EDT" }
        setStatus(Status.CONNECTING, null)
        try {
            val running = DriverLauncher.findRunning(connectionFile()).orElse(null) ?: startDriver()
            val c = BrowserDriverClient(running.baseUrl(), running.adminToken(), "intellij")
            info = running
            client = c
            events?.close()
            events = EventStream(running.baseUrl(), running.adminToken()).also { it.start() }
            setStatus(Status.CONNECTED, running.baseUrl())
            return c
        } catch (e: Exception) {
            setStatus(Status.DISCONNECTED, describe(e))
            throw e
        }
    }

    private fun startDriver(): ConnectionInfo {
        val s = CdbSettings.get().state
        val jar = InstallInfo.driverJar(s.driverJarPath)
        if (!s.autoStartDriver || jar == null) {
            throw IllegalStateException(
                "Driver is not running. Start it (bin/start-driver in the release bundle), run bin/setup once so the " +
                    "IDE can start it, or set the driver jar in Settings | Tools | Chromium Developer Bridge."
            )
        }
        log.info("Starting Chromium Developer Bridge driver from $jar")
        val file = connectionFile()
        return DriverLauncher.start(
            Path.of(jar), file, file.resolveSibling("logs").resolve("driver.log"), listOf(), Duration.ofSeconds(90)
        )
    }

    fun disconnect() {
        events?.close()
        events = null
        client = null
        info = null
        setStatus(Status.DISCONNECTED, null)
    }

    fun addStatusListener(parent: Disposable, listener: (Status, String?) -> Unit) {
        statusListeners.add(listener)
        com.intellij.openapi.util.Disposer.register(parent) { statusListeners.remove(listener) }
    }

    private fun setStatus(s: Status, detail: String?) {
        status = s
        statusListeners.forEach { l -> ApplicationManager.getApplication().invokeLater { l(s, detail) } }
    }

    override fun dispose() {
        events?.close()
    }

    companion object {
        fun get(): DriverService = ApplicationManager.getApplication().getService(DriverService::class.java)
        fun defaultConnectionFile(): Path = dev.cdb.client.ConnectionFiles.defaultPath()
    }
}
