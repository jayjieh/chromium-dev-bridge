package dev.cdb.intellij

import dev.cdb.protocol.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Location of an installed release bundle, written by `bin/setup` to ~/.chromium-dev-bridge/install.json.
 * Lets the plugin start the driver and show MCP configs without any manual settings.
 */
data class InstallInfo(val home: String?, val driverJar: String?, val mcpJar: String?, val version: String?) {
    companion object {
        fun file(): Path = Path.of(System.getProperty("user.home"), ".chromium-dev-bridge", "install.json")

        fun read(file: Path = file()): InstallInfo? {
            if (!Files.isRegularFile(file)) return null
            return try {
                val n = Json.mapper().readTree(file.toFile())
                fun s(k: String) = n.path(k).asText(null)?.takeIf { it.isNotBlank() }
                InstallInfo(s("home"), s("driverJar"), s("mcpJar"), s("version"))
            } catch (e: Exception) {
                null
            }
        }

        /** Driver jar: explicit setting first, then the installed bundle. */
        fun driverJar(settingsValue: String): String? =
            settingsValue.takeIf { it.isNotBlank() } ?: read()?.driverJar?.takeIf { Files.isRegularFile(Path.of(it)) }
    }
}
