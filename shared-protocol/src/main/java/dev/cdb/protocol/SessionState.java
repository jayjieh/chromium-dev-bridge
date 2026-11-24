package dev.cdb.protocol;

/** Browser session lifecycle states. See docs/architecture/SESSION_LIFECYCLE.md. */
public enum SessionState {
    CREATED, STARTING, RUNNING, DISCONNECTED, FAILED, STOPPING, STOPPED;

    public boolean isTerminal() {
        return this == STOPPED;
    }

    /** Allowed transitions of the session state machine. */
    public boolean canTransitionTo(SessionState next) {
        return switch (this) {
            case CREATED -> next == STARTING || next == STOPPING || next == FAILED;
            case STARTING -> next == RUNNING || next == FAILED || next == STOPPING;
            case RUNNING -> next == DISCONNECTED || next == STOPPING || next == FAILED || next == STARTING;
            case DISCONNECTED -> next == STARTING || next == STOPPING || next == FAILED;
            case FAILED -> next == STARTING || next == STOPPING;
            case STOPPING -> next == STOPPED;
            case STOPPED -> false;
        };
    }
}
