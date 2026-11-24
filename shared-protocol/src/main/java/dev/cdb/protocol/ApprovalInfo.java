package dev.cdb.protocol;

import java.time.Instant;

public record ApprovalInfo(ApprovalKind kind, String value, Instant grantedAt, String grantedBy) {
}
