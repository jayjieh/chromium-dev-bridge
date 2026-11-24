package dev.cdb.mcp;

import dev.cdb.client.BrowserDriverClient;
import dev.cdb.client.ConnectionFiles;
import dev.cdb.client.DriverLauncher;
import dev.cdb.protocol.ConnectionInfo;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Entry point: {@code java -jar cdb-mcp-server.jar [--connection-file PATH] [--driver-jar PATH]}.
 * Speaks MCP over stdio. stdout carries protocol messages only; diagnostics go to stderr.
 */
public final class McpServerMain {
    private McpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path connectionFile = ConnectionFiles.defaultPath();
        Path driverJar = envPath("CDB_DRIVER_JAR");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--connection-file" -> connectionFile = Path.of(args[++i]);
                case "--driver-jar" -> driverJar = Path.of(args[++i]);
                case "--help", "-h" -> {
                    System.err.println("Usage: java -jar cdb-mcp-server.jar [--connection-file PATH] [--driver-jar PATH]");
                    return;
                }
                default -> System.err.println("Ignoring unknown argument " + args[i]);
            }
        }
        if (driverJar == null) driverJar = siblingDriverJar();
        Path finalConnection = connectionFile;
        Path finalJar = driverJar;
        Supplier<BrowserDriverClient> clientFactory = () -> connect(finalConnection, finalJar);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8)); // keep stray prints off the protocol stream
        new McpServer(new BrowserTools(clientFactory), System.in, out).run();
    }

    static BrowserDriverClient connect(Path connectionFile, Path driverJar) {
        Optional<ConnectionInfo> running = DriverLauncher.findRunning(connectionFile);
        ConnectionInfo info;
        if (running.isPresent()) {
            info = running.get();
        } else if (driverJar != null && Files.isRegularFile(driverJar)) {
            try {
                System.err.println("[cdb-mcp] starting driver " + driverJar);
                info = DriverLauncher.start(driverJar, connectionFile, connectionFile.resolveSibling("logs").resolve("driver.log"),
                        List.of(), Duration.ofSeconds(90));
            } catch (Exception e) {
                throw new IllegalStateException("Could not start the browser driver: " + e.getMessage(), e);
            }
        } else {
            throw new IllegalStateException("The Chromium Developer Bridge driver is not running (no healthy driver at "
                    + connectionFile + "). Start it with scripts/<os>/start-driver, from the IntelliJ plugin, or pass --driver-jar.");
        }
        return new BrowserDriverClient(info.baseUrl(), info.agentToken(), System.getenv().getOrDefault("CDB_CLIENT_NAME", "mcp"));
    }

    private static Path envPath(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : Path.of(v);
    }

    private static Path siblingDriverJar() {
        try {
            Path self = Path.of(McpServerMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = self.resolveSibling("cdb-driver.jar");
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }
}
