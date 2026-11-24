package dev.cdb.core.scenario;

import dev.cdb.core.BridgeException;
import dev.cdb.core.Principal;
import dev.cdb.core.network.NetworkLog;
import dev.cdb.core.security.Redactor;
import dev.cdb.core.session.SessionManager;
import dev.cdb.protocol.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Executes declarative JSON browser scenarios against a session and produces a masked report. */
public class ScenarioRunner {
    private static final int MAX_STEPS = 500;
    private final SessionManager sessions;
    private final Function<String, String> env;

    public ScenarioRunner(SessionManager sessions) {
        this(sessions, System::getenv);
    }

    public ScenarioRunner(SessionManager sessions, Function<String, String> env) {
        this.sessions = sessions;
        this.env = env;
    }

    public ScenarioReport run(Principal p, String sessionId, Scenario scenario) {
        if (scenario == null || scenario.steps() == null || scenario.steps().isEmpty()) {
            throw BridgeException.validation("Scenario needs at least one step");
        }
        if (scenario.steps().size() > MAX_STEPS) throw BridgeException.validation("Scenario has more than " + MAX_STEPS + " steps");
        sessions.get(p, sessionId); // access check up front
        Map<String, String> fixture = null;
        if (scenario.fixture() != null) {
            fixture = scenario.fixtures() == null ? null : scenario.fixtures().get(scenario.fixture());
            if (fixture == null) throw BridgeException.validation("Unknown fixture: " + scenario.fixture());
        }
        VariableResolver vars = new VariableResolver(fixture, scenario.variables(), env);
        int defaultTimeout = scenario.defaultTimeoutMs() == null ? 10_000 : scenario.defaultTimeoutMs();
        boolean screenshotOnFailure = scenario.screenshotOnFailure() == null || scenario.screenshotOnFailure();

        long consoleStart = sessions.console(p, sessionId, new ConsoleQuery(null, null, null, null, null, null, 1)).lastSeq();
        long consoleBaseline = latestSeq(p, sessionId, consoleStart);
        long networkBaseline = latestNetworkSeq(p, sessionId);

        Instant started = Instant.now();
        List<StepResult> results = new ArrayList<>();
        boolean failed = false;
        int passed = 0, failedCount = 0, skipped = 0;
        for (int i = 0; i < scenario.steps().size(); i++) {
            ScenarioStep step = scenario.steps().get(i);
            String description = step.description() != null ? step.description() : describe(step);
            if (failed) {
                results.add(new StepResult(i, step.action(), vars.mask(description), false, 0, "skipped", null));
                skipped++;
                continue;
            }
            long t0 = System.nanoTime();
            try {
                String msg = execute(p, sessionId, scenario, step, vars, defaultTimeout, consoleBaseline, networkBaseline);
                results.add(new StepResult(i, step.action(), vars.mask(description), true, ms(t0), vars.mask(msg), null));
                passed++;
            } catch (RuntimeException e) {
                failed = true;
                failedCount++;
                String shot = null;
                if (screenshotOnFailure) {
                    try {
                        shot = sessions.screenshot(p, sessionId, ScreenshotRequest.viewport("failure-step-" + i)).screenshotId();
                    } catch (RuntimeException ignored) {
                        // session may be gone
                    }
                }
                results.add(new StepResult(i, step.action(), vars.mask(description), false, ms(t0),
                        vars.mask(Redactor.text(e.getMessage(), false)), shot));
            }
        }
        List<String> consoleErrors = new ArrayList<>();
        sessions.console(p, sessionId, new ConsoleQuery(null, ConsoleLevel.ERROR, null, null, null, consoleBaseline, 50))
                .items().forEach(c -> consoleErrors.add(vars.mask(c.message())));
        List<String> failedRequests = new ArrayList<>();
        sessions.network(p, sessionId, new NetworkQuery(null, null, true, null, null, null, null, networkBaseline, 50))
                .items().forEach(n -> failedRequests.add(n.method() + " " + vars.mask(n.url()) + " -> "
                        + (n.status() != null ? n.status() : n.failureText())));
        return new ScenarioReport(vars.mask(scenario.name()), sessionId, !failed, started,
                Duration.between(started, Instant.now()).toMillis(), passed, failedCount, skipped, results, consoleErrors, failedRequests);
    }

    private long latestSeq(Principal p, String sessionId, long hint) {
        long seq = hint;
        EventPage<ConsoleEntry> page;
        do {
            page = sessions.console(p, sessionId, new ConsoleQuery(null, null, null, null, null, seq, 1000));
            seq = page.lastSeq();
        } while (page.hasMore());
        return seq;
    }

    private long latestNetworkSeq(Principal p, String sessionId) {
        long seq = 0;
        EventPage<NetworkEntry> page;
        do {
            page = sessions.network(p, sessionId, new NetworkQuery(null, null, null, null, null, null, null, seq, 1000));
            seq = page.lastSeq();
        } while (page.hasMore());
        return seq;
    }

    private String execute(Principal p, String id, Scenario sc, ScenarioStep st, VariableResolver vars, int defaultTimeout,
                           long consoleBaseline, long networkBaseline) {
        String action = st.action() == null ? "" : st.action();
        int timeout = st.timeoutMs() != null ? st.timeoutMs() : defaultTimeout;
        Locator locator = locator(st, vars);
        switch (action) {
            case "navigate" -> {
                String url = vars.resolve(st.url());
                if (url == null) {
                    if (sc.baseUrl() == null || st.path() == null) throw new IllegalArgumentException("navigate needs url, or baseUrl and path");
                    url = stripSlash(vars.resolve(sc.baseUrl())) + vars.resolve(st.path());
                }
                NavigationResult r = sessions.navigate(p, id, new NavigateRequest(url, null, WaitUntil.LOAD, timeout));
                return "Loaded " + r.url() + (r.status() != null ? " (" + r.status() + ")" : "");
            }
            case "click", "doubleClick", "hover", "check", "uncheck" -> {
                ActionType t = switch (action) {
                    case "click" -> ActionType.CLICK;
                    case "doubleClick" -> ActionType.DOUBLE_CLICK;
                    case "hover" -> ActionType.HOVER;
                    case "check" -> ActionType.CHECK;
                    default -> ActionType.UNCHECK;
                };
                return act(p, id, ActionRequest.of(t, require(locator, action)).withTimeout(timeout));
            }
            case "fill", "type" -> {
                String value = vars.resolve(st.value());
                if (value == null) throw new IllegalArgumentException(action + " needs a value");
                ActionRequest r = new ActionRequest(action.equals("fill") ? ActionType.FILL : ActionType.TYPE, null,
                        require(locator, action), null, value, null, null, null, null, null, null, null, timeout);
                return act(p, id, r);
            }
            case "press" -> {
                if (st.key() == null) throw new IllegalArgumentException("press needs a key");
                return act(p, id, ActionRequest.press(locator, st.key()).withTimeout(timeout));
            }
            case "select" -> {
                List<String> values = st.values() != null ? st.values().stream().map(vars::resolve).toList()
                        : st.value() != null ? List.of(vars.resolve(st.value())) : null;
                if (values == null) throw new IllegalArgumentException("select needs value or values");
                return act(p, id, ActionRequest.select(require(locator, action), values).withTimeout(timeout));
            }
            case "scroll" -> {
                int dy = st.value() == null ? 500 : Integer.parseInt(vars.resolve(st.value()));
                return act(p, id, ActionRequest.scroll(locator, 0, dy).withTimeout(timeout));
            }
            case "wait", "assertVisible", "assertHidden" -> {
                ElementState state = action.equals("assertHidden") ? ElementState.HIDDEN : ElementState.VISIBLE;
                WaitRequest w = new WaitRequest(null, locator, locator != null ? state : null, null,
                        vars.resolve(st.urlPattern()), locator == null ? vars.resolve(st.text()) : null, timeout);
                if (locator == null && w.text() == null && w.urlPattern() == null) {
                    if (action.equals("wait")) {
                        w = new WaitRequest(null, null, null, WaitUntil.LOAD, null, null, timeout);
                    } else {
                        throw new IllegalArgumentException(action + " needs locator or text");
                    }
                }
                if (action.equals("assertHidden") && locator == null && w.text() != null) {
                    w = new WaitRequest(null, Locator.text(w.text()), ElementState.HIDDEN, null, null, null, timeout);
                }
                WaitResult r = sessions.waitFor(p, id, w);
                if (!r.satisfied()) throw new IllegalStateException(r.message() != null ? r.message() : "Condition not met");
                return r.message() != null ? r.message() : "ok";
            }
            case "assertText" -> {
                String expected = vars.resolve(st.text());
                if (expected == null) throw new IllegalArgumentException("assertText needs text");
                ActionResult r = sessions.perform(p, id, ActionRequest.of(ActionType.GET_TEXT, require(locator, action)).withTimeout(timeout));
                if (!r.success()) throw new IllegalStateException(r.message());
                String actual = String.valueOf(r.value());
                if (!actual.contains(expected)) throw new IllegalStateException("Text \"" + truncate(actual) + "\" does not contain \"" + expected + "\"");
                return "Text matches";
            }
            case "assertUrl" -> {
                String pattern = vars.resolve(st.urlPattern() != null ? st.urlPattern() : st.text());
                if (pattern == null) throw new IllegalArgumentException("assertUrl needs urlPattern or text");
                WaitResult r = sessions.waitFor(p, id, new WaitRequest(null, null, null, null,
                        pattern.contains("*") ? pattern : "**" + pattern + "**", null, timeout));
                if (!r.satisfied()) {
                    throw new IllegalStateException("URL " + sessions.page(p, id, null).url() + " does not match " + pattern);
                }
                return "URL matches " + pattern;
            }
            case "assertTitle" -> {
                String expected = vars.resolve(st.text());
                String title = sessions.page(p, id, null).title();
                if (expected == null || title == null || !title.contains(expected)) {
                    throw new IllegalStateException("Title \"" + title + "\" does not contain \"" + expected + "\"");
                }
                return "Title matches";
            }
            case "assertNoConsoleErrors" -> {
                EventPage<ConsoleEntry> errors = sessions.console(p, id,
                        new ConsoleQuery(null, ConsoleLevel.ERROR, null, null, null, consoleBaseline, 10));
                if (!errors.items().isEmpty()) {
                    throw new IllegalStateException(errors.items().size() + " console error(s), first: " + errors.items().get(0).message());
                }
                return "No console errors";
            }
            case "assertNetworkStatus" -> {
                if (st.urlPattern() == null || st.status() == null) throw new IllegalArgumentException("assertNetworkStatus needs urlPattern and status");
                String pattern = vars.resolve(st.urlPattern());
                NetworkEntry last = awaitCompletedRequest(p, id, pattern, networkBaseline, timeout);
                if (last.status() == null || !last.status().equals(st.status())) {
                    throw new IllegalStateException("Expected status " + st.status() + " for " + NetworkLog.summary(last).get("url")
                            + " but got " + (last.status() != null ? last.status() : last.failureText()));
                }
                return "Status " + st.status();
            }
            case "screenshot" -> {
                ScreenshotInfo info = sessions.screenshot(p, id, new ScreenshotRequest(null, st.name(), false, locator,
                        ImageFormat.PNG, null, null, true, false));
                return "Screenshot " + info.screenshotId();
            }
            case "assertScreenshot" -> {
                if (st.baseline() == null) throw new IllegalArgumentException("assertScreenshot needs baseline");
                ScreenshotInfo info = sessions.screenshot(p, id, new ScreenshotRequest(null, st.baseline(), false, locator,
                        ImageFormat.PNG, null, null, true, false));
                if (!sessions.screenshotStore().hasBaseline(st.baseline())) {
                    sessions.saveBaseline(p, new BaselineRequest(st.baseline(), info.screenshotId()));
                    return "Baseline " + st.baseline() + " created";
                }
                CompareResult c = sessions.compare(p, new CompareRequest(info.screenshotId(), null, st.baseline(), null,
                        st.maxDiffRatio()));
                if (!c.match()) throw new IllegalStateException("Visual difference: " + c.message() + " (diff " + c.diffScreenshotId() + ")");
                return c.message();
            }
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
    }

    /** Waits until the latest request matching the pattern has completed or failed. */
    private NetworkEntry awaitCompletedRequest(Principal p, String id, String pattern, long baseline, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        NetworkEntry last = null;
        while (true) {
            List<NetworkEntry> matches = sessions.network(p, id,
                    new NetworkQuery(null, null, null, null, null, pattern, null, baseline, 1000)).items();
            if (!matches.isEmpty()) {
                last = matches.get(matches.size() - 1);
                if (last.state() != NetworkState.PENDING) return last;
            }
            if (System.currentTimeMillis() >= deadline) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (last == null) throw new IllegalStateException("No request matched " + pattern);
        return last;
    }

    private String act(Principal p, String id, ActionRequest r) {
        ActionResult result = sessions.perform(p, id, r);
        if (!result.success()) throw new IllegalStateException(result.message());
        return "ok";
    }

    private static Locator locator(ScenarioStep st, VariableResolver vars) {
        Locator l = st.locator();
        if (l != null && !l.isEmpty()) {
            return new Locator(l.ref(), l.role(), vars.resolve(l.name()), l.exact(), l.testId(), vars.resolve(l.label()),
                    vars.resolve(l.placeholder()), vars.resolve(l.text()), l.css(), l.xpath(), l.nth());
        }
        String action = st.action() == null ? "" : st.action();
        boolean textIsLocator = st.text() != null && !action.equals("assertText") && !action.equals("assertTitle")
                && !action.equals("assertUrl") && !action.equals("wait") && !action.equals("assertVisible")
                && !action.equals("assertHidden");
        return textIsLocator ? Locator.text(vars.resolve(st.text())) : null;
    }

    private static Locator require(Locator l, String action) {
        if (l == null) throw new IllegalArgumentException(action + " needs a locator");
        return l;
    }

    private static String describe(ScenarioStep st) {
        StringBuilder sb = new StringBuilder(st.action() == null ? "?" : st.action());
        if (st.locator() != null) sb.append(' ').append(st.locator().describe());
        else if (st.text() != null) sb.append(" \"").append(st.text()).append('"');
        if (st.path() != null) sb.append(' ').append(st.path());
        if (st.url() != null) sb.append(' ').append(st.url());
        if (st.name() != null) sb.append(' ').append(st.name());
        return sb.toString();
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
