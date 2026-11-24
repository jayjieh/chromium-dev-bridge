package dev.cdb.core.session;

import dev.cdb.core.AuditSink;
import dev.cdb.core.BridgeException;
import dev.cdb.core.Clock;
import dev.cdb.core.CoreSettings;
import dev.cdb.core.DriverMetrics;
import dev.cdb.core.Principal;
import dev.cdb.core.console.SourceMapResolver;
import dev.cdb.core.events.EventBus;
import dev.cdb.core.events.RingBuffer;
import dev.cdb.core.security.ActionPolicy;
import dev.cdb.core.security.Redactor;
import dev.cdb.core.security.UrlPolicy;
import dev.cdb.core.spi.BrowserEventListener;
import dev.cdb.core.spi.BrowserProvider;
import dev.cdb.core.spi.BrowserSessionDriver;
import dev.cdb.core.spi.CapturedImage;
import dev.cdb.core.spi.SessionLaunchSpec;
import dev.cdb.core.visual.ImageDiff;
import dev.cdb.core.visual.ScreenshotStore;
import dev.cdb.protocol.*;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the core engine: owns sessions, enforces authorization and security policy, redacts captured data
 * and publishes events. All public methods are thread-safe.
 */
public class SessionManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*");
    private static final Set<String> CHANNELS = Set.of("chromium", "chrome", "msedge", "chrome-beta", "msedge-beta");
    private static final int MAX_INLINE_IMAGE = 5 * 1024 * 1024;

    private final CoreSettings settings;
    private final BrowserProvider provider;
    private final EventBus bus;
    private final UrlPolicy urlPolicy;
    private final ActionPolicy actionPolicy;
    private final ScreenshotStore screenshots;
    private final DriverMetrics metrics;
    private final AuditSink audit;
    private final Clock clock;
    private final SourceMapResolver sourceMaps;
    private final Map<String, ManagedSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService maintenance;
    private volatile boolean closed;

    public SessionManager(CoreSettings settings, BrowserProvider provider, EventBus bus, DriverMetrics metrics,
                          AuditSink audit, Clock clock) {
        this.settings = settings;
        this.provider = provider;
        this.bus = bus;
        this.metrics = metrics == null ? DriverMetrics.NOOP : metrics;
        this.audit = audit == null ? AuditSink.NOOP : audit;
        this.clock = clock == null ? Clock.SYSTEM : clock;
        this.urlPolicy = new UrlPolicy(settings.allowedHosts(), settings.allowFileUrls());
        this.actionPolicy = new ActionPolicy(urlPolicy);
        this.screenshots = new ScreenshotStore(settings.screenshotsDir(), settings.baselinesDir(),
                settings.screenshotsPerSession(), settings.screenshotMaxAge(), this.clock);
        this.sourceMaps = settings.sourceMaps() ? SourceMapResolver.withHttp(urlPolicy::isDevelopmentUrl) : null;
        this.maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cdb-maintenance");
            t.setDaemon(true);
            return t;
        });
    }

    public CoreSettings settings() { return settings; }
    public UrlPolicy urlPolicy() { return urlPolicy; }
    public BrowserProvider provider() { return provider; }
    public EventBus bus() { return bus; }
    public ScreenshotStore screenshotStore() { return screenshots; }

    // ------------------------------------------------------------------ sessions

    public SessionInfo create(Principal principal, SessionCreateRequest request) {
        if (closed) throw new BridgeException(ErrorCode.SESSION_NOT_RUNNING, "Driver is shutting down");
        SessionCreateRequest r = request == null ? SessionCreateRequest.defaults() : request;
        validate(principal, r);
        long active = sessions.values().stream().filter(s -> !s.state().isTerminal() && s.state() != SessionState.FAILED).count();
        if (active >= settings.maxSessions()) {
            throw new BridgeException(ErrorCode.LIMIT_EXCEEDED, "Maximum number of sessions (" + settings.maxSessions() + ") reached");
        }
        if (r.initialUrl() != null) checkUrl(null, r.initialUrl());

        String id = "session-" + UUID.randomUUID().toString().substring(0, 8);
        Viewport viewport = r.viewportPreset() != null ? r.viewportPreset().viewport()
                : r.viewport() != null ? r.viewport() : settings.defaultViewport();
        Duration idle = r.idleTimeoutSeconds() != null ? Duration.ofSeconds(r.idleTimeoutSeconds()) : settings.idleTimeout();
        boolean agentAccess = principal.isAdmin() && Boolean.TRUE.equals(r.agentAccess());
        ManagedSession s = new ManagedSession(id, r.projectId(), r.label(), principal.name(), agentAccess, clock.now(), idle,
                settings.consoleBufferSize(), settings.networkBufferSize(), viewport);
        SessionLaunchSpec spec = new SessionLaunchSpec(id, r.headless() != null ? r.headless() : settings.defaultHeadless(),
                viewport, r.locale(), r.deviceScaleFactor(), r.userAgent(),
                r.isolation() == null ? SessionIsolation.SHARED : r.isolation(),
                r.profileName() == null ? null : settings.profilesDir().resolve(r.profileName()),
                r.channel(), r.connectEndpoint(), settings.downloadsDir().resolve(id), settings.acceptDownloads(),
                url -> urlPolicy.isAllowed(url, s.approvedOrigins()),
                settings.operationTimeout(), settings.navigationTimeout(), settings.captureBodies(), settings.maxBodyBytes());
        s.spec(spec);
        sessions.put(id, s);
        audit.record(principal, "session.create", id, "requested", Map.of("headless", spec.headless(),
                "isolation", spec.isolation().name()));
        start(s, false);
        if (r.initialUrl() != null) {
            navigate(principal, id, new NavigateRequest(r.initialUrl()));
        }
        return s.info();
    }

    private void start(ManagedSession s, boolean restart) {
        Instant t0 = clock.now();
        changeState(s, SessionState.STARTING);
        try {
            BrowserSessionDriver d;
            if (restart && s.driver() != null) {
                d = s.driver();
                d.restart();
            } else {
                d = provider.open(s.spec(), new Listener(s));
                s.driver(d);
            }
            s.providerInfo(d.providerInfo());
            s.activeTabId(d.activeTabId());
            s.failureReason(null);
            changeState(s, SessionState.RUNNING);
            metrics.sessionStarted(Duration.between(t0, clock.now()));
            if (!restart) bus.publish(DriverEvent.of(EventTypes.SESSION_CREATED, s.id(), null, s.info()));
        } catch (RuntimeException e) {
            s.failureReason(e.getMessage());
            changeState(s, SessionState.FAILED);
            metrics.sessionFailed();
            log.warn("Session {} failed to start: {}", s.id(), e.getMessage());
            if (e instanceof BridgeException be) throw be;
            throw new BridgeException(ErrorCode.BROWSER_ERROR, "Browser failed to start: " + e.getMessage(), e);
        }
    }

    public List<SessionInfo> list(Principal principal, String projectId) {
        return sessions.values().stream()
                .filter(s -> s.canAccess(principal))
                .filter(s -> projectId == null || projectId.equals(s.projectId()))
                .map(ManagedSession::info)
                .sorted(Comparator.comparing(SessionInfo::createdAt))
                .toList();
    }

    /** True when the session exists and the principal may use it. */
    public boolean canAccess(Principal principal, String sessionId) {
        ManagedSession s = sessionId == null ? null : sessions.get(sessionId);
        return s != null && s.canAccess(principal);
    }

    public SessionInfo get(Principal principal, String sessionId) {
        return access(principal, sessionId).info();
    }

    public void close(Principal principal, String sessionId) {
        ManagedSession s = access(principal, sessionId);
        closeInternal(s, "closed by " + principal.name());
        audit.record(principal, "session.close", sessionId, "ok", Map.of());
    }

    private void closeInternal(ManagedSession s, String reason) {
        synchronized (s) {
            if (s.state() == SessionState.STOPPED || s.state() == SessionState.STOPPING) return;
            changeState(s, SessionState.STOPPING);
        }
        try {
            BrowserSessionDriver d = s.driver();
            if (d != null) d.close();
        } catch (RuntimeException e) {
            log.warn("Error closing session {}: {}", s.id(), e.toString());
        } finally {
            changeState(s, SessionState.STOPPED);
            sessions.remove(s.id());
            screenshots.forgetSession(s.id());
            metrics.sessionClosed();
            bus.publish(DriverEvent.of(EventTypes.SESSION_CLOSED, s.id(), null, Map.of("reason", reason)));
        }
    }

    public SessionInfo restart(Principal principal, String sessionId) {
        ManagedSession s = access(principal, sessionId);
        if (s.state() == SessionState.STOPPING || s.state() == SessionState.STOPPED) {
            throw new BridgeException(ErrorCode.SESSION_NOT_RUNNING, "Session is closing");
        }
        start(s, s.driver() != null);
        audit.record(principal, "session.restart", sessionId, "ok", Map.of());
        return s.info();
    }

    public SessionInfo setViewport(Principal principal, String sessionId, ViewportRequest request) {
        Viewport v;
        try {
            v = request.resolve();
        } catch (IllegalArgumentException e) {
            throw BridgeException.validation(e.getMessage());
        }
        return run(principal, sessionId, s -> {
            s.requireRunning().setViewport(null, v);
            s.viewport(v);
            return s.info();
        });
    }

    // ------------------------------------------------------------------ navigation & tabs

    public NavigationResult navigate(Principal principal, String sessionId, NavigateRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) throw BridgeException.validation("url is required");
        return run(principal, sessionId, s -> {
            String url = resolveUrl(s, request.url());
            checkUrl(s, url);
            Duration timeout = timeout(request.timeoutMs(), settings.navigationTimeout());
            try {
                NavigationResult r = s.requireRunning().navigate(request.tabId(), url, request.waitUntil(), timeout);
                s.currentUrl(r.url());
                s.title(r.title());
                audit.record(principal, "navigate", sessionId, "ok", Map.of("url", Redactor.url(url)));
                return redact(r);
            } catch (BridgeException e) {
                metrics.navigationFailed();
                audit.record(principal, "navigate", sessionId, "failed:" + e.code(), Map.of("url", Redactor.url(url)));
                throw e;
            }
        });
    }

    public NavigationResult reload(Principal p, String sessionId, String tabId) {
        return run(p, sessionId, s -> track(s, s.requireRunning().reload(tabId, settings.navigationTimeout())));
    }

    public NavigationResult back(Principal p, String sessionId, String tabId) {
        return run(p, sessionId, s -> track(s, s.requireRunning().back(tabId, settings.navigationTimeout())));
    }

    public NavigationResult forward(Principal p, String sessionId, String tabId) {
        return run(p, sessionId, s -> track(s, s.requireRunning().forward(tabId, settings.navigationTimeout())));
    }

    public PageInfo stop(Principal p, String sessionId, String tabId) {
        return run(p, sessionId, s -> s.requireRunning().stop(tabId));
    }

    public PageInfo page(Principal p, String sessionId, String tabId) {
        return run(p, sessionId, s -> {
            PageInfo info = s.requireRunning().pageInfo(tabId);
            return new PageInfo(info.tabId(), Redactor.url(info.url()), info.title(), info.readyState());
        });
    }

    public WaitResult waitFor(Principal p, String sessionId, WaitRequest request) {
        if (request == null) throw BridgeException.validation("wait request is required");
        if ((request.locator() == null || request.locator().isEmpty()) && request.loadState() == null
                && request.urlPattern() == null && request.text() == null) {
            throw BridgeException.validation("Provide locator, text, loadState or urlPattern");
        }
        return run(p, sessionId, s -> s.requireRunning().waitFor(request, timeout(request.timeoutMs(), settings.operationTimeout())));
    }

    public List<TabInfo> tabs(Principal p, String sessionId) {
        return run(p, sessionId, s -> s.requireRunning().tabs().stream().map(SessionManager::redact).toList());
    }

    public TabInfo openTab(Principal p, String sessionId, OpenTabRequest request) {
        return run(p, sessionId, s -> {
            String url = request == null || request.url() == null || request.url().isBlank() ? null : resolveUrl(s, request.url());
            if (url != null) checkUrl(s, url);
            TabInfo t = s.requireRunning().openTab(url);
            s.activeTabId(t.tabId());
            return redact(t);
        });
    }

    public void closeTab(Principal p, String sessionId, String tabId) {
        run(p, sessionId, s -> {
            s.requireRunning().closeTab(tabId);
            s.activeTabId(s.requireRunning().activeTabId());
            return null;
        });
    }

    public TabInfo activateTab(Principal p, String sessionId, String tabId) {
        return run(p, sessionId, s -> {
            TabInfo t = s.requireRunning().activateTab(tabId);
            s.activeTabId(t.tabId());
            s.currentUrl(t.url());
            return redact(t);
        });
    }

    // ------------------------------------------------------------------ inspection

    public DomSnapshot snapshot(Principal p, String sessionId, SnapshotRequest request) {
        SnapshotRequest r = request == null ? SnapshotRequest.of(SnapshotMode.COMPACT) : request;
        SnapshotMode mode = r.mode() == null ? SnapshotMode.COMPACT : r.mode();
        int def = switch (mode) {
            case FULL -> 2000;
            case COMPACT -> 500;
            case INTERACTIVE -> 300;
            case ACCESSIBILITY -> 0;
        };
        int max = r.maxElements() == null ? def : Math.max(1, Math.min(5000, r.maxElements()));
        SnapshotRequest normalized = new SnapshotRequest(r.tabId(), mode, r.root(), max, r.includeHidden(), r.role(), r.text());
        return run(p, sessionId, s -> {
            DomSnapshot snap = s.requireRunning().snapshot(normalized);
            List<DomElement> els = snap.elements() == null ? List.of() : snap.elements().stream().map(SessionManager::redact).toList();
            return new DomSnapshot(snap.pageTitle(), Redactor.url(snap.url()), snap.tabId(), snap.mode(), snap.elementCount(),
                    snap.truncated(), els, Redactor.text(snap.ariaSnapshot(), false));
        });
    }

    public ElementDetails inspect(Principal p, String sessionId, InspectRequest request) {
        if (request == null || request.locator() == null || request.locator().isEmpty()) {
            throw BridgeException.validation("locator is required");
        }
        return run(p, sessionId, s -> s.requireRunning().inspect(request));
    }

    public AccessibilityReport accessibility(Principal p, String sessionId, String tabId, String root) {
        return run(p, sessionId, s -> {
            AccessibilityReport r = s.requireRunning().accessibility(tabId, root);
            return new AccessibilityReport(r.tabId(), Redactor.url(r.url()), Redactor.text(r.ariaSnapshot(), false), r.issues());
        });
    }

    public EventPage<ConsoleEntry> console(Principal p, String sessionId, ConsoleQuery q) {
        ManagedSession s = access(p, sessionId);
        ConsoleQuery query = q == null ? ConsoleQuery.all() : q;
        int limit = query.limit() == null ? 200 : Math.max(1, Math.min(1000, query.limit()));
        String text = query.text() == null ? null : query.text().toLowerCase(Locale.ROOT);
        String source = query.source() == null ? null : query.source().toLowerCase(Locale.ROOT);
        RingBuffer.Page<ConsoleEntry> page = s.console().query(query.afterSeq() == null ? 0 : query.afterSeq(), limit, e ->
                (query.tabId() == null || query.tabId().equals(e.tabId()))
                        && (query.level() == null || e.level().ordinal() >= query.level().ordinal())
                        && (query.since() == null || !e.timestamp().isBefore(query.since()))
                        && (text == null || e.message().toLowerCase(Locale.ROOT).contains(text))
                        && (source == null || matchesSource(e, source)));
        return new EventPage<>(page.items(), page.lastSeq(), page.hasMore(), page.dropped());
    }

    private static boolean matchesSource(ConsoleEntry e, String source) {
        return (e.source() != null && e.source().url() != null && e.source().url().toLowerCase(Locale.ROOT).contains(source))
                || (e.originalSource() != null && e.originalSource().file() != null
                && e.originalSource().file().toLowerCase(Locale.ROOT).contains(source));
    }

    public EventPage<NetworkEntry> network(Principal p, String sessionId, NetworkQuery q) {
        ManagedSession s = access(p, sessionId);
        return s.network().query(q == null ? NetworkQuery.all() : q);
    }

    // ------------------------------------------------------------------ actions

    public ActionResult perform(Principal p, String sessionId, ActionRequest request) {
        validateAction(request);
        return run(p, sessionId, s -> {
            actionPolicy.check(request.type(), s.currentUrl(), s.approvalKinds());
            Instant t0 = clock.now();
            ActionResult r;
            try {
                r = s.requireRunning().perform(request, timeout(request.timeoutMs(), settings.operationTimeout()));
            } catch (BridgeException e) {
                metrics.action(request.type().name(), Duration.between(t0, clock.now()), false);
                audit.record(p, "action", sessionId, "failed:" + e.code(), actionDetails(request));
                throw e;
            }
            metrics.action(request.type().name(), Duration.between(t0, clock.now()), r.success());
            audit.record(p, "action", sessionId, r.success() ? "ok" : "failed:" + r.errorCode(), actionDetails(request));
            if (r.pageUrl() != null) s.currentUrl(r.pageUrl());
            Object value = r.value() instanceof String str ? Redactor.text(str, false) : r.value();
            return new ActionResult(r.success(), r.type(), r.durationMs(), value, r.message(), Redactor.url(r.pageUrl()),
                    r.navigated(), r.errorCode());
        });
    }

    private static Map<String, Object> actionDetails(ActionRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", r.type().name());
        if (r.locator() != null) m.put("locator", r.locator().describe());
        if (r.value() != null) m.put("valueLength", r.value().length());
        return m;
    }

    static void validateAction(ActionRequest r) {
        if (r == null || r.type() == null) throw BridgeException.validation("type is required");
        if (r.type().needsLocator() && (r.locator() == null || r.locator().isEmpty())) {
            throw BridgeException.validation(r.type() + " needs a locator");
        }
        switch (r.type()) {
            case FILL, TYPE -> {
                if (r.value() == null) throw BridgeException.validation(r.type() + " needs a value");
                if (r.value().length() > 100_000) throw new BridgeException(ErrorCode.PAYLOAD_TOO_LARGE, "value too long");
            }
            case SELECT -> {
                if (r.value() == null && (r.values() == null || r.values().isEmpty())) {
                    throw BridgeException.validation("SELECT needs value or values");
                }
            }
            case PRESS -> {
                if (r.key() == null || r.key().isBlank()) throw BridgeException.validation("PRESS needs a key");
            }
            case DRAG_AND_DROP -> {
                if (r.target() == null || r.target().isEmpty()) throw BridgeException.validation("DRAG_AND_DROP needs a target");
            }
            case GET_ATTRIBUTE -> {
                if (r.attribute() == null || r.attribute().isBlank()) throw BridgeException.validation("GET_ATTRIBUTE needs an attribute");
            }
            case CLICK_AT -> {
                if (r.x() == null || r.y() == null) throw BridgeException.validation("CLICK_AT needs x and y");
            }
            default -> { }
        }
    }

    public EvaluateResult evaluate(Principal p, String sessionId, EvaluateRequest request) {
        if (!settings.allowEvaluate()) {
            throw new BridgeException(ErrorCode.UNSUPPORTED, "JavaScript evaluation is disabled (cdb.security.allow-evaluate=false)");
        }
        p.requireAdmin("JavaScript evaluation");
        if (request == null || request.expression() == null || request.expression().isBlank()) {
            throw BridgeException.validation("expression is required");
        }
        if (request.expression().length() > 20_000) throw new BridgeException(ErrorCode.PAYLOAD_TOO_LARGE, "expression too long");
        return run(p, sessionId, s -> {
            if (!s.approvalKinds().contains(ApprovalKind.EVALUATE)) {
                throw new BridgeException(ErrorCode.APPROVAL_REQUIRED, "Grant an EVALUATE approval for this session first");
            }
            Object v = s.requireRunning().evaluate(request.tabId(), request.expression());
            audit.record(p, "evaluate", sessionId, "ok", Map.of("expressionLength", request.expression().length()));
            return new EvaluateResult(v);
        });
    }

    // ------------------------------------------------------------------ screenshots

    public ScreenshotInfo screenshot(Principal p, String sessionId, ScreenshotRequest request) {
        ScreenshotRequest r = request == null ? ScreenshotRequest.viewport(null) : request;
        if (r.name() != null && !r.name().matches("[A-Za-z0-9 ._-]{1,100}")) {
            throw BridgeException.validation("name must match [A-Za-z0-9 ._-]{1,100}");
        }
        return run(p, sessionId, s -> {
            BrowserSessionDriver d = s.requireRunning();
            if (r.viewportPreset() != null) {
                d.setViewport(r.tabId(), r.viewportPreset().viewport());
                s.viewport(r.viewportPreset().viewport());
            }
            CapturedImage img = d.screenshot(r);
            ScreenshotStore.Stored stored = screenshots.save(s.id(), s.owner(), r.name(), img.bytes(), img.format(),
                    Redactor.url(img.pageUrl()));
            bus.publish(DriverEvent.of(EventTypes.SCREENSHOT_CREATED, s.id(), r.tabId(), stored.info()));
            if (Boolean.TRUE.equals(r.inline()) && img.bytes().length <= MAX_INLINE_IMAGE) {
                return stored.info().withBase64(Base64.getEncoder().encodeToString(img.bytes()));
            }
            return stored.info();
        });
    }

    /** Live-preview frame (JPEG, viewport, sensitive fields masked). Not stored in the screenshot history. */
    public byte[] preview(Principal p, String sessionId, String tabId, Integer quality) {
        int q = quality == null ? 60 : Math.max(10, Math.min(90, quality));
        return run(p, sessionId, s -> s.requireRunning().screenshot(new ScreenshotRequest(tabId, null, false, null,
                ImageFormat.JPEG, q, null, true, false)).bytes());
    }

    public List<ScreenshotInfo> screenshots(Principal p, String sessionId) {
        access(p, sessionId);
        return screenshots.list(sessionId);
    }

    public record Image(byte[] bytes, ImageFormat format) { }

    public Image screenshotImage(Principal p, String screenshotId) {
        ScreenshotStore.Stored s = screenshotAccess(p, screenshotId);
        return new Image(screenshots.bytes(screenshotId), s.info().format());
    }

    private ScreenshotStore.Stored screenshotAccess(Principal p, String screenshotId) {
        ScreenshotStore.Stored s = screenshots.get(screenshotId);
        ManagedSession session = sessions.get(s.info().sessionId());
        boolean ok = p.isAdmin() || s.owner().equals(p.name()) || (session != null && session.canAccess(p));
        if (!ok) throw new BridgeException(ErrorCode.NOT_FOUND, "Screenshot not found: " + screenshotId);
        return s;
    }

    public CompareResult compare(Principal p, CompareRequest request) {
        if (request == null || request.candidateScreenshotId() == null) throw BridgeException.validation("candidateScreenshotId is required");
        ScreenshotStore.Stored candidate = screenshotAccess(p, request.candidateScreenshotId());
        byte[] baseline;
        if (request.baselineScreenshotId() != null) {
            screenshotAccess(p, request.baselineScreenshotId());
            baseline = screenshots.bytes(request.baselineScreenshotId());
        } else if (request.baselineName() != null) {
            baseline = screenshots.baseline(request.baselineName());
        } else {
            throw BridgeException.validation("baselineScreenshotId or baselineName is required");
        }
        double threshold = request.pixelThreshold() == null ? 0.1 : request.pixelThreshold();
        double maxRatio = request.maxDiffRatio() == null ? 0.001 : request.maxDiffRatio();
        try {
            ImageDiff.Result diff = ImageDiff.compare(baseline, screenshots.bytes(candidate.info().screenshotId()), threshold);
            String diffId = null;
            if (diff.diffPixels() > 0) {
                diffId = screenshots.save(candidate.info().sessionId(), candidate.owner(), "diff", diff.diffPng(),
                        ImageFormat.PNG, candidate.info().pageUrl()).info().screenshotId();
            }
            boolean match = !diff.sizeMismatch() && diff.ratio() <= maxRatio;
            String msg = diff.sizeMismatch() ? "Image sizes differ"
                    : String.format(Locale.ROOT, "%.4f%% of pixels differ (limit %.4f%%)", diff.ratio() * 100, maxRatio * 100);
            return new CompareResult(match, diff.diffPixels(), diff.totalPixels(), diff.ratio(), diff.sizeMismatch(), diffId, msg);
        } catch (IOException e) {
            throw new BridgeException(ErrorCode.VALIDATION_FAILED, "Could not decode images: " + e.getMessage(), e);
        }
    }

    public BaselineInfo saveBaseline(Principal p, BaselineRequest request) {
        if (request == null || request.name() == null || request.screenshotId() == null) {
            throw BridgeException.validation("name and screenshotId are required");
        }
        screenshotAccess(p, request.screenshotId());
        BaselineInfo info = screenshots.saveBaseline(request.name(), request.screenshotId());
        audit.record(p, "baseline.save", null, "ok", Map.of("name", request.name()));
        return info;
    }

    public List<BaselineInfo> baselines(Principal p) {
        return screenshots.baselines();
    }

    // ------------------------------------------------------------------ approvals

    public ApprovalInfo approve(Principal p, String sessionId, ApprovalRequest request) {
        p.requireAdmin("Granting approvals");
        if (request == null || request.kind() == null) throw BridgeException.validation("kind is required");
        ManagedSession s = access(p, sessionId);
        String value = null;
        if (request.kind() == ApprovalKind.ORIGIN) {
            if (request.value() == null) throw BridgeException.validation("ORIGIN approvals need a value (https://host[:port])");
            try {
                value = UrlPolicy.normalizeOrigin(request.value());
            } catch (IllegalArgumentException e) {
                throw BridgeException.validation(e.getMessage());
            }
        }
        ApprovalInfo info = new ApprovalInfo(request.kind(), value, clock.now(), p.name());
        s.addApproval(info);
        audit.record(p, "approval.grant", sessionId, "ok", value == null ? Map.of("kind", request.kind().name())
                : Map.of("kind", request.kind().name(), "value", value));
        return info;
    }

    public List<ApprovalInfo> approvals(Principal p, String sessionId) {
        return access(p, sessionId).approvals();
    }

    // ------------------------------------------------------------------ maintenance

    /** Closes idle sessions and applies screenshot retention. Called periodically by the host application. */
    public void maintain() {
        Instant now = clock.now();
        for (ManagedSession s : List.copyOf(sessions.values())) {
            if (Duration.between(s.lastActivityAt(), now).compareTo(s.idleTimeout()) > 0) {
                log.info("Closing idle session {}", s.id());
                closeInternal(s, "idle timeout");
            } else if (s.state() == SessionState.RUNNING && s.driver() != null && !s.driver().isConnected()) {
                handleDisconnect(s, "browser connection lost");
            }
        }
        screenshots.enforceRetention();
        for (ManagedSession s : sessions.values()) {
            metrics.bufferUtilization("console", (double) s.console().size() / s.console().capacity());
            metrics.bufferUtilization("network", (double) s.network().size() / s.network().capacity());
        }
    }

    public int activeSessionCount() {
        return (int) sessions.values().stream().filter(s -> s.state() == SessionState.RUNNING).count();
    }

    @Override
    public void close() {
        closed = true;
        for (ManagedSession s : List.copyOf(sessions.values())) {
            closeInternal(s, "driver shutdown");
        }
        maintenance.shutdownNow();
        try {
            provider.close();
        } catch (RuntimeException e) {
            log.warn("Provider close failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ internals

    ManagedSession access(Principal p, String sessionId) {
        ManagedSession s = sessions.get(sessionId);
        if (s == null || !s.canAccess(p)) {
            // Same error for "missing" and "not yours": do not leak other principals' session ids.
            throw new BridgeException(ErrorCode.SESSION_NOT_FOUND, "Session not found: " + sessionId);
        }
        return s;
    }

    private <T> T run(Principal p, String sessionId, Function<ManagedSession, T> fn) {
        ManagedSession s = access(p, sessionId);
        s.touch(clock.now());
        return fn.apply(s);
    }

    private NavigationResult track(ManagedSession s, NavigationResult r) {
        s.currentUrl(r.url());
        s.title(r.title());
        return redact(r);
    }

    private void checkUrl(ManagedSession s, String url) {
        UrlPolicy.Verdict v = urlPolicy.evaluate(url, s == null ? Set.of() : s.approvedOrigins());
        switch (v) {
            case ALLOWED_DEV, ALLOWED_APPROVED -> { }
            case INVALID -> throw BridgeException.validation("Invalid URL: " + Redactor.url(url));
            case DENIED_SCHEME -> throw new BridgeException(ErrorCode.URL_NOT_ALLOWED, "URL scheme not allowed: " + Redactor.url(url));
            case DENIED_NEEDS_APPROVAL -> throw new BridgeException(ErrorCode.URL_NOT_ALLOWED,
                    "Not a development host: " + Redactor.url(url) + ". The user must approve this origin for the session "
                            + "(IDE: Sessions > Approve origin, or POST /api/v1/sessions/{id}/approvals with the admin token).");
        }
    }

    private static String resolveUrl(ManagedSession s, String url) {
        String u = url.trim();
        if (u.startsWith("/") && !u.startsWith("//") && s.currentUrl() != null && s.currentUrl().startsWith("http")) {
            return URI.create(s.currentUrl()).resolve(u).toString();
        }
        if (!u.contains("://") && !u.startsWith("about:") && !u.startsWith("/") && u.matches("^(localhost|127\\.0\\.0\\.1|\\[::1]).*")) {
            return "http://" + u;
        }
        return u;
    }

    private static Duration timeout(Integer ms, Duration def) {
        if (ms == null) return def;
        return Duration.ofMillis(Math.max(100, Math.min(300_000, ms)));
    }

    private void changeState(ManagedSession s, SessionState next) {
        SessionState prev = s.transition(next);
        if (prev != next) {
            bus.publish(DriverEvent.of(EventTypes.SESSION_STATE, s.id(), null, Map.of("from", prev.name(), "to", next.name())));
        }
    }

    private void handleDisconnect(ManagedSession s, String reason) {
        synchronized (s) {
            if (s.state() != SessionState.RUNNING) return;
            s.failureReason(reason);
            changeState(s, SessionState.DISCONNECTED);
        }
        metrics.browserCrashed();
        log.warn("Session {} disconnected: {}", s.id(), reason);
        if (settings.autoRestart() && !closed) {
            maintenance.schedule(() -> {
                try {
                    if (s.state() == SessionState.DISCONNECTED) {
                        start(s, true);
                        log.info("Session {} recovered", s.id());
                    }
                } catch (RuntimeException e) {
                    log.warn("Automatic restart of {} failed: {}", s.id(), e.getMessage());
                }
            }, 500, TimeUnit.MILLISECONDS);
        }
    }

    private void validate(Principal p, SessionCreateRequest r) {
        if (r.profileName() != null && !PROFILE_NAME.matcher(r.profileName()).matches()) {
            throw BridgeException.validation("profileName must match [A-Za-z0-9_-]{1,64}");
        }
        if (r.channel() != null && !CHANNELS.contains(r.channel())) {
            throw BridgeException.validation("channel must be one of " + CHANNELS);
        }
        if (r.locale() != null && !LOCALE.matcher(r.locale()).matches()) throw BridgeException.validation("Invalid locale");
        if (r.deviceScaleFactor() != null && (r.deviceScaleFactor() < 0.5 || r.deviceScaleFactor() > 4)) {
            throw BridgeException.validation("deviceScaleFactor must be between 0.5 and 4");
        }
        if (r.userAgent() != null && r.userAgent().length() > 512) throw BridgeException.validation("userAgent too long");
        if (r.idleTimeoutSeconds() != null && r.idleTimeoutSeconds() < 30) throw BridgeException.validation("idleTimeoutSeconds must be >= 30");
        if (r.projectId() != null && r.projectId().length() > 200) throw BridgeException.validation("projectId too long");
        if (r.label() != null && r.label().length() > 200) throw BridgeException.validation("label too long");
        if (r.connectEndpoint() != null) {
            p.requireAdmin("Attaching to an existing browser");
            try {
                URI uri = URI.create(r.connectEndpoint());
                String host = uri.getHost();
                if (host == null || !(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1"))) {
                    throw BridgeException.validation("connectEndpoint must be a loopback URL");
                }
            } catch (IllegalArgumentException e) {
                throw BridgeException.validation("Invalid connectEndpoint");
            }
        }
        if (r.profileName() != null && r.isolation() == SessionIsolation.SHARED) {
            throw BridgeException.validation("profileName requires isolation=PROCESS (persistent profiles get their own browser)");
        }
    }

    private static NavigationResult redact(NavigationResult r) {
        return new NavigationResult(r.tabId(), Redactor.url(r.url()), r.title(), r.status(), r.durationMs());
    }

    private static TabInfo redact(TabInfo t) {
        return new TabInfo(t.tabId(), Redactor.url(t.url()), t.title(), t.active());
    }

    private static DomElement redact(DomElement e) {
        return new DomElement(e.reference(), e.parent(), e.depth(), e.tag(), e.role(), Redactor.text(e.name(), false),
                Redactor.text(e.text(), false), e.visible(), e.interactive(), e.disabled(), e.checked(),
                Redactor.text(e.value(), false), e.attributes(), e.bounds());
    }

    /** Receives provider callbacks for one session (browser thread: never block). */
    private final class Listener implements BrowserEventListener {
        private final ManagedSession s;

        Listener(ManagedSession s) {
            this.s = s;
        }

        @Override
        public void onConsole(ConsoleEntry raw) {
            List<StackFrame> frames = raw.stack() == null ? List.of() : raw.stack().stream()
                    .map(f -> new StackFrame(f.function(), redactLocation(f.location()), null)).toList();
            ConsoleEntry stored = s.console().add(seq -> new ConsoleEntry(seq, raw.eventId(), s.id(), raw.tabId(),
                    raw.timestamp(), raw.level(), raw.kind(), Redactor.text(raw.message(), false), redactLocation(raw.source()),
                    null, frames));
            bus.publish(DriverEvent.of(stored.level() == ConsoleLevel.ERROR ? EventTypes.CONSOLE_ERROR : EventTypes.CONSOLE_MESSAGE,
                    s.id(), stored.tabId(), stored));
            if (stored.message() != null && stored.message().contains("CORS policy")) markCors(stored.message());
            if (sourceMaps != null && stored.level().ordinal() >= ConsoleLevel.WARNING.ordinal()) {
                maintenance.execute(() -> enrich(stored));
            }
        }

        private void enrich(ConsoleEntry e) {
            try {
                SourceLocation original = e.source() == null ? null : sourceMaps.resolve(e.source()).orElse(null);
                List<StackFrame> frames = new ArrayList<>();
                boolean changed = original != null;
                for (StackFrame f : e.stack()) {
                    SourceLocation o = sourceMaps.resolve(f.location()).orElse(null);
                    changed |= o != null;
                    frames.add(f.withOriginal(o));
                }
                if (original == null && !frames.isEmpty() && frames.get(0).original() != null) original = frames.get(0).original();
                if (changed) {
                    SourceLocation finalOriginal = original;
                    ConsoleEntry enriched = e.withOriginal(finalOriginal, frames);
                    if (s.console().replace(e.seq(), old -> enriched)) {
                        // Same eventId and seq as the first event: clients replace the entry they already show.
                        bus.publish(DriverEvent.of(enriched.level() == ConsoleLevel.ERROR ? EventTypes.CONSOLE_ERROR
                                : EventTypes.CONSOLE_MESSAGE, s.id(), enriched.tabId(), enriched));
                    }
                }
            } catch (RuntimeException ex) {
                log.debug("Source map enrichment failed: {}", ex.toString());
            }
        }

        private void markCors(String message) {
            int at = message.indexOf("at '");
            if (at < 0) return;
            int end = message.indexOf('\'', at + 4);
            if (end < 0) return;
            String url = Redactor.url(message.substring(at + 4, end));
            NetworkEntry e = s.network().findLatestByUrl(url);
            if (e != null && !e.corsError()) {
                s.network().upsert(new NetworkEntry(0, e.requestId(), e.sessionId(), e.tabId(), e.method(), e.url(),
                        e.resourceType(), e.state(), e.status(), e.statusText(), e.mimeType(), e.startedAt(), e.durationMs(),
                        e.failureText(), e.redirectedFrom(), true, e.webSocket(), e.requestHeaders(), e.responseHeaders(),
                        e.requestBody(), e.responseBody()));
            }
        }

        @Override
        public void onNetwork(NetworkEntry raw) {
            int max = settings.maxBodyBytes();
            NetworkEntry clean = new NetworkEntry(0, raw.requestId(), s.id(), raw.tabId(), raw.method(), Redactor.url(raw.url()),
                    raw.resourceType(), raw.state(), raw.status(), raw.statusText(), raw.mimeType(), raw.startedAt(),
                    raw.durationMs(), raw.failureText(), Redactor.url(raw.redirectedFrom()), raw.corsError(), raw.webSocket(),
                    Redactor.headers(raw.requestHeaders()), Redactor.headers(raw.responseHeaders()),
                    Redactor.body(raw.requestBody(), raw.requestHeaders() == null ? null : raw.requestHeaders().get("content-type"), max),
                    Redactor.body(raw.responseBody(), raw.mimeType(), max));
            NetworkEntry stored = s.network().upsert(clean);
            String type = switch (stored.state()) {
                case PENDING -> EventTypes.NETWORK_REQUEST;
                case COMPLETED -> EventTypes.NETWORK_RESPONSE;
                case FAILED -> EventTypes.NETWORK_FAILED;
            };
            bus.publish(DriverEvent.of(type, s.id(), stored.tabId(), stored));
        }

        @Override
        public void onTabCreated(TabInfo tab) {
            bus.publish(DriverEvent.of(EventTypes.TAB_CREATED, s.id(), tab.tabId(), redact(tab)));
        }

        @Override
        public void onTabClosed(String tabId) {
            bus.publish(DriverEvent.of(EventTypes.TAB_CLOSED, s.id(), tabId, Map.of("tabId", tabId)));
        }

        @Override
        public void onNavigation(String tabId, String url, NavigationPhase phase, String error) {
            String clean = Redactor.url(url);
            if (phase == NavigationPhase.COMPLETED && (tabId == null || tabId.equals(s.activeTabId()))) s.currentUrl(url);
            String type = switch (phase) {
                case STARTED -> EventTypes.NAVIGATION_STARTED;
                case COMPLETED -> EventTypes.NAVIGATION_COMPLETED;
                case FAILED -> EventTypes.NAVIGATION_FAILED;
            };
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", clean);
            if (error != null) data.put("error", Redactor.text(error, false));
            bus.publish(DriverEvent.of(type, s.id(), tabId, data));
        }

        @Override
        public void onNavigationBlocked(String tabId, String url) {
            bus.publish(DriverEvent.of(EventTypes.NAVIGATION_BLOCKED, s.id(), tabId, Map.of("url", Redactor.url(url),
                    "reason", "origin not allowed for this session")));
        }

        @Override
        public void onDisconnected(String reason) {
            if (s.state() == SessionState.STOPPING || s.state() == SessionState.STOPPED) return;
            maintenance.execute(() -> handleDisconnect(s, reason));
        }

        private SourceLocation redactLocation(SourceLocation l) {
            return l == null ? null : new SourceLocation(Redactor.url(l.url()), l.file(), l.line(), l.column());
        }
    }
}
