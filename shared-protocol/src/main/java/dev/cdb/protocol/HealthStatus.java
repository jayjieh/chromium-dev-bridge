package dev.cdb.protocol;

public record HealthStatus(String status, String version, long uptimeSeconds) {
}
