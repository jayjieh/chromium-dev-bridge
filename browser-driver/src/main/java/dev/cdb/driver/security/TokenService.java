package dev.cdb.driver.security;

import dev.cdb.core.Principal;
import dev.cdb.driver.CdbProperties;
import dev.cdb.protocol.ConnectionInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Holds the randomly generated admin and agent tokens and resolves bearer tokens to principals. */
@Component
public class TokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] admin;
    private final byte[] agent;
    private final String adminToken;
    private final String agentToken;

    private final String extensionToken;
    private final byte[] extension;

    public TokenService(CdbProperties props) {
        this.extensionToken = persistentExtensionToken(props.dataDir().resolve("extension-token"));
        this.extension = extensionToken.getBytes(StandardCharsets.UTF_8);
        this.adminToken = valueOrRandom(props.security().adminToken());
        this.agentToken = valueOrRandom(props.security().agentToken());
        if (adminToken.equals(agentToken)) throw new IllegalStateException("admin and agent tokens must differ");
        this.admin = adminToken.getBytes(StandardCharsets.UTF_8);
        this.agent = agentToken.getBytes(StandardCharsets.UTF_8);
    }

    private static String valueOrRandom(String configured) {
        if (configured != null && !configured.isBlank()) {
            if (configured.length() < 24) throw new IllegalStateException("Configured tokens must be at least 24 characters");
            return configured;
        }
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Constant-time token comparison. */
    public Optional<Principal> authenticate(String bearer, String clientName) {
        if (bearer == null) return Optional.empty();
        byte[] given = bearer.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(given, admin)) return Optional.of(Principal.admin());
        if (MessageDigest.isEqual(given, agent)) return Optional.of(Principal.agent(clientName));
        return Optional.empty();
    }

    /**
     * The extension pairing token survives restarts (stored owner-only in the data dir) so users pair Chrome once.
     * Delete the file to rotate it.
     */
    static String persistentExtensionToken(java.nio.file.Path file) {
        try {
            if (java.nio.file.Files.isRegularFile(file)) {
                String t = java.nio.file.Files.readString(file).trim();
                if (t.length() >= 32) return t;
            }
            java.nio.file.Files.createDirectories(file.getParent());
            String t = valueOrRandom(null);
            java.nio.file.Files.writeString(file, t);
            ConnectionFileWriter.restrictToOwner(file);
            return t;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create extension token at " + file + ": " + e.getMessage(), e);
        }
    }

    /** Constant-time check of a token presented by the Chrome extension. */
    public boolean isExtensionToken(String token) {
        return token != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), extension);
    }

    public String extensionToken() {
        return extensionToken;
    }

    public ConnectionInfo connectionInfo(String baseUrl, int port, String version) {
        return new ConnectionInfo(baseUrl, port, adminToken, agentToken, ProcessHandle.current().pid(), java.time.Instant.now(), version,
                extensionToken);
    }

    public String adminToken() {
        return adminToken;
    }

    public String agentToken() {
        return agentToken;
    }
}
