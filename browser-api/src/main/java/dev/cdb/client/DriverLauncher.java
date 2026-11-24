package dev.cdb.client;

import dev.cdb.protocol.ConnectionInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds a running driver or starts one from its executable jar, then waits until it is healthy.
 * Used by the MCP server and the IntelliJ plugin.
 */
public final class DriverLauncher {
    private DriverLauncher() {
    }

    /** Returns connection info of a healthy running driver, if any. */
    public static Optional<ConnectionInfo> findRunning(Path connectionFile) {
        Optional<ConnectionInfo> info = ConnectionFiles.read(connectionFile);
        if (info.isEmpty()) return Optional.empty();
        BrowserDriverClient probe = new BrowserDriverClient(info.get().baseUrl(), null, "probe");
        return probe.isUp() ? info : Optional.empty();
    }

    /**
     * Starts "java -jar driverJar" (detached; output to logFile) and waits for the connection file and health check.
     */
    public static ConnectionInfo start(Path driverJar, Path connectionFile, Path logFile, List<String> extraArgs, Duration wait)
            throws IOException {
        if (!Files.isRegularFile(driverJar)) throw new IOException("Driver jar not found: " + driverJar);
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-jar", driverJar.toString()));
        cmd.add("--cdb.security.connection-file=" + connectionFile);
        if (extraArgs != null) cmd.addAll(extraArgs);
        Files.createDirectories(logFile.getParent());
        Files.deleteIfExists(connectionFile);
        new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile())).start();
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ConnectionInfo> running = findRunning(connectionFile);
            if (running.isPresent()) return running.get();
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for driver");
            }
        }
        throw new IOException("Driver did not become healthy within " + wait.toSeconds() + " s; see " + logFile);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
