package dev.cdb.protocol;

public record ApprovalRequest(ApprovalKind kind, String value, String reason) {
}
