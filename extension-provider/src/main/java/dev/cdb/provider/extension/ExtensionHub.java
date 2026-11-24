package dev.cdb.provider.extension;

import dev.cdb.protocol.Json;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts connections from Chromium Developer Bridge extensions (one per Chrome profile) and turns shared tabs into
 * driver sessions. Transport-agnostic: the driver feeds WebSocket frames in through {@link #received}.
 */
public class ExtensionHub implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ExtensionHub.class);

    /** One WebSocket connection to an extension. */
    public interface Transport {
        String id();

        void send(String text);

        void close();
    }

    public record ConnectionStatus(String connectionId, String extensionVersion, String browser, List<Integer> sharedTabs) { }

    private final ExtensionSessionRegistrar registrar;
    private final Duration commandTimeout;
    private final boolean allowApproveFromExtension;
    private final Map<String, ExtensionConnection> connections = new ConcurrentHashMap<>();
    private final ExecutorService lifecycle = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cdb-extension");
        t.setDaemon(true);
        return t;
    });

    public ExtensionHub(ExtensionSessionRegistrar registrar, Duration commandTimeout, boolean allowApproveFromExtension) {
        this.registrar = registrar;
        this.commandTimeout = commandTimeout;
        this.allowApproveFromExtension = allowApproveFromExtension;
    }

    public void connected(Transport transport) {
        connections.put(transport.id(), new ExtensionConnection(transport, registrar, lifecycle, commandTimeout,
                allowApproveFromExtension, Json.mapper()));
        log.info("Chrome extension connected ({})", transport.id());
    }

    public void received(Transport transport, String text) {
        ExtensionConnection c = connections.get(transport.id());
        if (c != null) c.handle(text);
    }

    public void disconnected(Transport transport) {
        ExtensionConnection c = connections.remove(transport.id());
        if (c != null) {
            c.closed();
            log.info("Chrome extension disconnected ({})", transport.id());
        }
    }

    public List<ConnectionStatus> status() {
        return connections.values().stream().map(ExtensionConnection::status).toList();
    }

    @Override
    public void close() {
        connections.values().forEach(ExtensionConnection::closed);
        connections.clear();
        lifecycle.shutdownNow();
    }
}
