package dev.cdb.core.spi;

import dev.cdb.protocol.AccessibilityReport;
import dev.cdb.protocol.ActionRequest;
import dev.cdb.protocol.ActionResult;
import dev.cdb.protocol.DomSnapshot;
import dev.cdb.protocol.ElementDetails;
import dev.cdb.protocol.InspectRequest;
import dev.cdb.protocol.NavigationResult;
import dev.cdb.protocol.PageInfo;
import dev.cdb.protocol.ProviderInfo;
import dev.cdb.protocol.ScreenshotRequest;
import dev.cdb.protocol.SnapshotRequest;
import dev.cdb.protocol.TabInfo;
import dev.cdb.protocol.Viewport;
import dev.cdb.protocol.WaitRequest;
import dev.cdb.protocol.WaitResult;
import dev.cdb.protocol.WaitUntil;
import java.time.Duration;
import java.util.List;

/**
 * One isolated browser session (a browser context with tabs). Methods are synchronous and thread-safe:
 * providers serialise them onto their own browser thread. A null tabId means "active tab".
 */
public interface BrowserSessionDriver extends AutoCloseable {
    String contextId();

    ProviderInfo providerInfo();

    boolean isConnected();

    List<TabInfo> tabs();

    String activeTabId();

    TabInfo openTab(String url);

    void closeTab(String tabId);

    TabInfo activateTab(String tabId);

    NavigationResult navigate(String tabId, String url, WaitUntil waitUntil, Duration timeout);

    NavigationResult reload(String tabId, Duration timeout);

    NavigationResult back(String tabId, Duration timeout);

    NavigationResult forward(String tabId, Duration timeout);

    PageInfo stop(String tabId);

    PageInfo pageInfo(String tabId);

    WaitResult waitFor(WaitRequest request, Duration timeout);

    ActionResult perform(ActionRequest request, Duration timeout);

    DomSnapshot snapshot(SnapshotRequest request);

    ElementDetails inspect(InspectRequest request);

    AccessibilityReport accessibility(String tabId, String root);

    CapturedImage screenshot(ScreenshotRequest request);

    void setViewport(String tabId, Viewport viewport);

    Object evaluate(String tabId, String expression);

    /** Recreates the context (relaunching the browser if it crashed) and reopens the last URLs. */
    void restart();

    @Override
    void close();
}
