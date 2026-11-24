package dev.cdb.core.security;

import dev.cdb.core.BridgeException;
import dev.cdb.protocol.ActionType;
import dev.cdb.protocol.ApprovalKind;
import dev.cdb.protocol.ErrorCode;
import java.util.Set;

/**
 * Guards actions with real-world consequences. On development origins all actions are allowed. On approved
 * non-development origins, actions that change state need a SENSITIVE_ACTIONS approval.
 */
public class ActionPolicy {
    private final UrlPolicy urlPolicy;

    public ActionPolicy(UrlPolicy urlPolicy) {
        this.urlPolicy = urlPolicy;
    }

    public void check(ActionType type, String currentUrl, Set<ApprovalKind> approvals) {
        if (type.isReadOnly()) return;
        if (currentUrl == null || urlPolicy.isDevelopmentUrl(currentUrl)) return;
        if (approvals.contains(ApprovalKind.SENSITIVE_ACTIONS)) return;
        throw new BridgeException(ErrorCode.APPROVAL_REQUIRED,
                "Action " + type + " on a non-development origin needs a SENSITIVE_ACTIONS approval from the user");
    }
}
