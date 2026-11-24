package dev.cdb.driver.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cdb.driver.CdbProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes ~/.chromium-dev-bridge/driver.json (port + tokens) readable only by the current user, so local clients
 * (IntelliJ plugin, MCP server) can connect. Deleted on shutdown.
 */
@Component
public class ConnectionFileWriter {
    private static final Logger log = LoggerFactory.getLogger(ConnectionFileWriter.class);
    private final CdbProperties props;
    private final TokenService tokens;
    private final ObjectMapper mapper;
    private volatile Path written;

    public ConnectionFileWriter(CdbProperties props, TokenService tokens, ObjectMapper mapper) {
        this.props = props;
        this.tokens = tokens;
        this.mapper = mapper;
    }

    @EventListener
    public void onStarted(WebServerInitializedEvent event) throws IOException {
        if (event.getApplicationContext().getServerNamespace() != null) return; // management port
        int port = event.getWebServer().getPort();
        Path file = props.security().connectionFile();
        Files.createDirectories(file.getParent());
        restrictToOwner(file.getParent());
        String version = getClass().getPackage().getImplementationVersion();
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                tokens.connectionInfo("http://127.0.0.1:" + port, port, version == null ? "dev" : version));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, json);
        restrictToOwner(tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        written = file;
        log.info("Driver listening on http://127.0.0.1:{} — connection details in {}", port, file);
    }

    @PreDestroy
    public void delete() {
        Path f = written;
        if (f == null) return;
        try {
            Files.deleteIfExists(f);
        } catch (IOException e) {
            log.debug("Could not delete connection file: {}", e.toString());
        }
    }

    static void restrictToOwner(Path p) {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------"));
            } else {
                AclFileAttributeView view = Files.getFileAttributeView(p, AclFileAttributeView.class);
                if (view == null) return;
                UserPrincipal owner = Files.getOwner(p);
                AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                view.setAcl(List.of(entry));
            }
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("Could not restrict permissions of {}: {}", p, e.toString());
        }
    }
}
