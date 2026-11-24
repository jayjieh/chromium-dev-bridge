package dev.cdb.driver.web;

import dev.cdb.core.Principal;
import dev.cdb.core.scenario.ScenarioRunner;
import dev.cdb.core.session.SessionManager;
import dev.cdb.protocol.*;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Session-scoped REST endpoints; see docs/api/OPENAPI.yaml. */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {
    private final SessionManager sessions;
    private final ScenarioRunner scenarios;

    public SessionController(SessionManager sessions, ScenarioRunner scenarios) {
        this.sessions = sessions;
        this.scenarios = scenarios;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionInfo create(Principal p, @RequestBody(required = false) SessionCreateRequest request) {
        return sessions.create(p, request);
    }

    @GetMapping
    public List<SessionInfo> list(Principal p, @RequestParam(required = false) String projectId) {
        return sessions.list(p, projectId);
    }

    @GetMapping("/{sessionId}")
    public SessionInfo get(Principal p, @PathVariable String sessionId) {
        return sessions.get(p, sessionId);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> close(Principal p, @PathVariable String sessionId) {
        sessions.close(p, sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sessionId}/restart")
    public SessionInfo restart(Principal p, @PathVariable String sessionId) {
        return sessions.restart(p, sessionId);
    }

    @PostMapping("/{sessionId}/viewport")
    public SessionInfo viewport(Principal p, @PathVariable String sessionId, @RequestBody ViewportRequest request) {
        return sessions.setViewport(p, sessionId, request);
    }

    @PostMapping("/{sessionId}/navigate")
    public NavigationResult navigate(Principal p, @PathVariable String sessionId, @RequestBody NavigateRequest request) {
        return sessions.navigate(p, sessionId, request);
    }

    @PostMapping("/{sessionId}/refresh")
    public NavigationResult refresh(Principal p, @PathVariable String sessionId, @RequestParam(required = false) String tabId) {
        return sessions.reload(p, sessionId, tabId);
    }

    @PostMapping("/{sessionId}/back")
    public NavigationResult back(Principal p, @PathVariable String sessionId, @RequestParam(required = false) String tabId) {
        return sessions.back(p, sessionId, tabId);
    }

    @PostMapping("/{sessionId}/forward")
    public NavigationResult forward(Principal p, @PathVariable String sessionId, @RequestParam(required = false) String tabId) {
        return sessions.forward(p, sessionId, tabId);
    }

    @PostMapping("/{sessionId}/stop")
    public PageInfo stop(Principal p, @PathVariable String sessionId, @RequestParam(required = false) String tabId) {
        return sessions.stop(p, sessionId, tabId);
    }

    @GetMapping("/{sessionId}/page")
    public PageInfo page(Principal p, @PathVariable String sessionId, @RequestParam(required = false) String tabId) {
        return sessions.page(p, sessionId, tabId);
    }

    @PostMapping("/{sessionId}/wait")
    public WaitResult waitFor(Principal p, @PathVariable String sessionId, @RequestBody WaitRequest request) {
        return sessions.waitFor(p, sessionId, request);
    }

    @GetMapping("/{sessionId}/tabs")
    public List<TabInfo> tabs(Principal p, @PathVariable String sessionId) {
        return sessions.tabs(p, sessionId);
    }

    @PostMapping("/{sessionId}/tabs")
    @ResponseStatus(HttpStatus.CREATED)
    public TabInfo openTab(Principal p, @PathVariable String sessionId, @RequestBody(required = false) OpenTabRequest request) {
        return sessions.openTab(p, sessionId, request);
    }

    @DeleteMapping("/{sessionId}/tabs/{tabId}")
    public ResponseEntity<Void> closeTab(Principal p, @PathVariable String sessionId, @PathVariable String tabId) {
        sessions.closeTab(p, sessionId, tabId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sessionId}/tabs/{tabId}/activate")
    public TabInfo activateTab(Principal p, @PathVariable String sessionId, @PathVariable String tabId) {
        return sessions.activateTab(p, sessionId, tabId);
    }

    @GetMapping("/{sessionId}/dom")
    public DomSnapshot dom(Principal p, @PathVariable String sessionId,
                           @RequestParam(required = false) String tabId,
                           @RequestParam(required = false) SnapshotMode mode,
                           @RequestParam(required = false) String root,
                           @RequestParam(required = false) Integer maxElements,
                           @RequestParam(required = false) Boolean includeHidden,
                           @RequestParam(required = false) String role,
                           @RequestParam(required = false) String text) {
        return sessions.snapshot(p, sessionId, new SnapshotRequest(tabId, mode, root, maxElements, includeHidden, role, text));
    }

    @PostMapping("/{sessionId}/elements/inspect")
    public ElementDetails inspect(Principal p, @PathVariable String sessionId, @RequestBody InspectRequest request) {
        return sessions.inspect(p, sessionId, request);
    }

    @GetMapping("/{sessionId}/accessibility")
    public AccessibilityReport accessibility(Principal p, @PathVariable String sessionId,
                                             @RequestParam(required = false) String tabId,
                                             @RequestParam(required = false) String root) {
        return sessions.accessibility(p, sessionId, tabId, root);
    }

    @GetMapping("/{sessionId}/console")
    public EventPage<ConsoleEntry> console(Principal p, @PathVariable String sessionId,
                                           @RequestParam(required = false) String tabId,
                                           @RequestParam(required = false) ConsoleLevel level,
                                           @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant since,
                                           @RequestParam(required = false) String text,
                                           @RequestParam(required = false) String source,
                                           @RequestParam(required = false) Long afterSeq,
                                           @RequestParam(required = false) Integer limit) {
        return sessions.console(p, sessionId, new ConsoleQuery(tabId, level, since, text, source, afterSeq, limit));
    }

    @GetMapping("/{sessionId}/network")
    public EventPage<NetworkEntry> network(Principal p, @PathVariable String sessionId,
                                           @RequestParam(required = false) String tabId,
                                           @RequestParam(required = false) Boolean fetchXhrOnly,
                                           @RequestParam(required = false) Boolean failedOnly,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String resourceType,
                                           @RequestParam(required = false) String urlPattern,
                                           @RequestParam(required = false) Integer minDurationMs,
                                           @RequestParam(required = false) Long afterSeq,
                                           @RequestParam(required = false) Integer limit) {
        return sessions.network(p, sessionId, new NetworkQuery(tabId, fetchXhrOnly, failedOnly, status, resourceType,
                urlPattern, minDurationMs, afterSeq, limit));
    }

    @PostMapping("/{sessionId}/actions")
    public ActionResult action(Principal p, @PathVariable String sessionId, @RequestBody ActionRequest request) {
        return sessions.perform(p, sessionId, request);
    }

    @PostMapping("/{sessionId}/evaluate")
    public EvaluateResult evaluate(Principal p, @PathVariable String sessionId, @RequestBody EvaluateRequest request) {
        return sessions.evaluate(p, sessionId, request);
    }

    @PostMapping("/{sessionId}/screenshots")
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenshotInfo screenshot(Principal p, @PathVariable String sessionId,
                                     @RequestBody(required = false) ScreenshotRequest request) {
        return sessions.screenshot(p, sessionId, request);
    }

    @GetMapping(value = "/{sessionId}/preview", produces = "image/jpeg")
    public byte[] preview(Principal p, @PathVariable String sessionId, @RequestParam(required = false) String tabId,
                          @RequestParam(required = false) Integer quality) {
        return sessions.preview(p, sessionId, tabId, quality);
    }

    @GetMapping("/{sessionId}/screenshots")
    public List<ScreenshotInfo> screenshots(Principal p, @PathVariable String sessionId) {
        return sessions.screenshots(p, sessionId);
    }

    @PostMapping("/{sessionId}/scenarios")
    public ScenarioReport scenario(Principal p, @PathVariable String sessionId, @RequestBody Scenario scenario) {
        return scenarios.run(p, sessionId, scenario);
    }

    @GetMapping("/{sessionId}/approvals")
    public List<ApprovalInfo> approvals(Principal p, @PathVariable String sessionId) {
        return sessions.approvals(p, sessionId);
    }

    @PostMapping("/{sessionId}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalInfo approve(Principal p, @PathVariable String sessionId, @RequestBody ApprovalRequest request) {
        return sessions.approve(p, sessionId, request);
    }
}
