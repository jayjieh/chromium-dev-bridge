package dev.cdb.core;

import dev.cdb.protocol.ErrorCode;

/**
 * Authenticated caller. ADMIN = the human user via the IDE/CLI. AGENT = AI agents via MCP.
 */
public record Principal(String name, Scope scope) {
    public enum Scope { ADMIN, AGENT }

    public static final Principal SYSTEM = new Principal("system", Scope.ADMIN);

    public static Principal admin() {
        return new Principal("admin", Scope.ADMIN);
    }

    public static Principal agent(String client) {
        String c = client == null || client.isBlank() ? "default" : client.replaceAll("[^A-Za-z0-9._-]", "").toLowerCase();
        if (c.isEmpty()) c = "default";
        if (c.length() > 64) c = c.substring(0, 64);
        return new Principal("agent:" + c, Scope.AGENT);
    }

    public boolean isAdmin() {
        return scope == Scope.ADMIN;
    }

    public void requireAdmin(String what) {
        if (!isAdmin()) {
            throw new BridgeException(ErrorCode.ACCESS_DENIED, what + " requires the admin token (human user)");
        }
    }
}
