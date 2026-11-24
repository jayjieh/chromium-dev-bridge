package dev.cdb.core.session;

import dev.cdb.core.BridgeException;
import dev.cdb.core.Principal;
import dev.cdb.core.events.RingBuffer;
import dev.cdb.core.network.NetworkLog;
import dev.cdb.core.spi.BrowserSessionDriver;
import dev.cdb.core.spi.SessionLaunchSpec;
import dev.cdb.protocol.ApprovalInfo;
import dev.cdb.protocol.ApprovalKind;
import dev.cdb.protocol.ConsoleEntry;
import dev.cdb.protocol.ErrorCode;
import dev.cdb.protocol.ProviderInfo;
import dev.cdb.protocol.SessionInfo;
import dev.cdb.protocol.SessionState;
import dev.cdb.protocol.Viewport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Mutable state of one session. Thread-safe. */
public final class ManagedSession {
    private final String id;
    private final String projectId;
    private final String label;
    private final String owner;
    private final boolean agentAccess;
    private final Instant createdAt;
    private final Duration idleTimeout;
    private final RingBuffer<ConsoleEntry> console;
    private final NetworkLog network;
    private final Set<String> approvedOrigins = ConcurrentHashMap.newKeySet();
    private final List<ApprovalInfo> approvals = new CopyOnWriteArrayList<>();

    private volatile SessionState state = SessionState.CREATED;
    private volatile Instant lastActivityAt;
    private volatile String failureReason;
    private volatile BrowserSessionDriver driver;
    private volatile SessionLaunchSpec spec;
    private volatile ProviderInfo providerInfo;
    private volatile Viewport viewport;
    private volatile String currentUrl;
    private volatile String title;
    private volatile String activeTabId;

    ManagedSession(String id, String projectId, String label, String owner, boolean agentAccess, Instant now,
                   Duration idleTimeout, int consoleSize, int networkSize, Viewport viewport) {
        this.id = id;
        this.projectId = projectId;
        this.label = label;
        this.owner = owner;
        this.agentAccess = agentAccess;
        this.createdAt = now;
        this.lastActivityAt = now;
        this.idleTimeout = idleTimeout;
        this.console = new RingBuffer<>(consoleSize);
        this.network = new NetworkLog(networkSize);
        this.viewport = viewport;
    }

    public String id() { return id; }
    public String projectId() { return projectId; }
    public String owner() { return owner; }
    public boolean agentAccess() { return agentAccess; }
    public SessionState state() { return state; }
    public Instant lastActivityAt() { return lastActivityAt; }
    public Duration idleTimeout() { return idleTimeout; }
    public RingBuffer<ConsoleEntry> console() { return console; }
    public NetworkLog network() { return network; }
    public Set<String> approvedOrigins() { return approvedOrigins; }
    public List<ApprovalInfo> approvals() { return List.copyOf(approvals); }
    public BrowserSessionDriver driver() { return driver; }
    public SessionLaunchSpec spec() { return spec; }
    public String currentUrl() { return currentUrl; }
    public Viewport viewport() { return viewport; }
    public String activeTabId() { return activeTabId; }

    void driver(BrowserSessionDriver d) { this.driver = d; }
    void spec(SessionLaunchSpec s) { this.spec = s; }
    void providerInfo(ProviderInfo p) { this.providerInfo = p; }
    void viewport(Viewport v) { this.viewport = v; }
    void currentUrl(String url) { if (url != null) this.currentUrl = url; }
    void title(String t) { if (t != null) this.title = t; }
    void activeTabId(String t) { if (t != null) this.activeTabId = t; }
    void failureReason(String r) { this.failureReason = r; }

    public void touch(Instant now) {
        lastActivityAt = now;
    }

    public boolean canAccess(Principal p) {
        return p.isAdmin() || owner.equals(p.name()) || agentAccess;
    }

    /** Applies a state transition; returns the previous state. */
    synchronized SessionState transition(SessionState next) {
        SessionState prev = state;
        if (prev == next) return prev;
        if (!prev.canTransitionTo(next)) {
            throw new BridgeException(ErrorCode.SESSION_NOT_RUNNING,
                    "Session " + id + " cannot go from " + prev + " to " + next);
        }
        state = next;
        return prev;
    }

    public BrowserSessionDriver requireRunning() {
        // A session that is (re)starting becomes usable within seconds: wait instead of failing the caller.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while ((state == SessionState.CREATED || state == SessionState.STARTING) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        BrowserSessionDriver d = driver;
        if (state != SessionState.RUNNING || d == null) {
            throw new BridgeException(ErrorCode.SESSION_NOT_RUNNING, "Session " + id + " is " + state
                    + (failureReason != null ? " (" + failureReason + ")" : "") + "; restart or recreate it");
        }
        return d;
    }

    void addApproval(ApprovalInfo info) {
        approvals.add(info);
        if (info.kind() == ApprovalKind.ORIGIN && info.value() != null) approvedOrigins.add(info.value());
    }

    public Set<ApprovalKind> approvalKinds() {
        Set<ApprovalKind> kinds = EnumSet.noneOf(ApprovalKind.class);
        for (ApprovalInfo a : approvals) kinds.add(a.kind());
        return kinds;
    }

    public SessionInfo info() {
        ProviderInfo p = providerInfo;
        String ctx = driver != null ? driver.contextId() : null;
        return new SessionInfo(id, projectId, label, ctx, activeTabId, currentUrl, title, createdAt, lastActivityAt, state,
                p, spec == null || spec.headless(), viewport, owner, agentAccess, failureReason);
    }

    List<String> recentErrors(int max) {
        List<String> out = new ArrayList<>();
        for (ConsoleEntry e : console.query(0, max, c -> c.level() == dev.cdb.protocol.ConsoleLevel.ERROR).items()) {
            out.add(e.message());
        }
        return out;
    }
}
