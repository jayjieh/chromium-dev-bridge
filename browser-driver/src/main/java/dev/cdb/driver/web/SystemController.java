package dev.cdb.driver.web;

import dev.cdb.core.Principal;
import dev.cdb.core.session.SessionManager;
import dev.cdb.protocol.ActionType;
import dev.cdb.protocol.Capabilities;
import dev.cdb.protocol.EventTypes;
import dev.cdb.protocol.HealthStatus;
import dev.cdb.protocol.SnapshotMode;
import dev.cdb.protocol.Viewport;
import dev.cdb.protocol.ViewportPreset;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SystemController {
    static final String VERSION = "0.1.0";
    private final SessionManager sessions;
    private volatile List<String> discovered;

    public SystemController(SessionManager sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/health")
    public HealthStatus health() {
        return new HealthStatus("UP", VERSION, ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities(Principal principal) {
        if (discovered == null) discovered = sessions.provider().discoveredBrowsers();
        Map<String, Viewport> presets = new LinkedHashMap<>();
        for (ViewportPreset p : ViewportPreset.values()) presets.put(p.name(), p.viewport());
        return new Capabilities(VERSION, List.of(sessions.provider().info()), Arrays.asList(SnapshotMode.values()),
                Arrays.asList(ActionType.values()), EventTypes.ALL, presets,
                sessions.settings().allowEvaluate() && principal.isAdmin(), sessions.urlPolicy().devHostPatterns(),
                sessions.settings().maxSessions(), discovered);
    }

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public ResponseEntity<byte[]> openApi() throws IOException {
        try (InputStream in = new ClassPathResource("static/api/OPENAPI.yaml").getInputStream()) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/yaml")).body(in.readAllBytes());
        }
    }
}
