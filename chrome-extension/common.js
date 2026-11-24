// Shared helpers for the service worker and the popup.

/** Parses "127.0.0.1:7878#token" (optionally prefixed with cdb://). Only loopback hosts are accepted. */
export function parsePairing(code) {
  const m = /^\s*(?:cdb:\/\/)?(127\.0\.0\.1|localhost|\[::1\]):(\d{2,5})#([A-Za-z0-9_-]{32,})\s*$/.exec(code || '');
  return m ? { host: m[1], port: Number(m[2]), token: m[3] } : null;
}

/** Development hosts the driver allows by default (others need the user's approval). */
export function isDevUrl(url) {
  try {
    const u = new URL(url);
    if (!['http:', 'https:'].includes(u.protocol)) return false;
    const h = u.hostname.toLowerCase();
    return h === 'localhost' || h === '127.0.0.1' || h === '[::1]' || h === '::1' || h.endsWith('.localhost') || h.endsWith('.test');
  } catch {
    return false;
  }
}

/** Pages Chrome does not allow extensions to debug. */
export function isRestrictedUrl(url) {
  return !url || /^(chrome|chrome-extension|edge|devtools|view-source|about):/i.test(url) && url !== 'about:blank'
    || url.startsWith('https://chromewebstore.google.com') || url.startsWith('https://chrome.google.com/webstore');
}

/**
 * CDP commands the driver may send. Anything else (e.g. Network.getAllCookies, Storage.getCookies) is refused, so
 * cookies and other site data can't be exported even by a compromised driver.
 */
export const ALLOWED_COMMANDS = new Set([
  'Page.enable', 'Page.getFrameTree', 'Page.navigate', 'Page.reload', 'Page.getNavigationHistory',
  'Page.navigateToHistoryEntry', 'Page.stopLoading', 'Page.captureScreenshot', 'Page.getLayoutMetrics',
  'Runtime.enable', 'Runtime.evaluate', 'Network.enable', 'Log.enable',
  'Input.dispatchMouseEvent', 'Input.dispatchKeyEvent',
  'Emulation.setDeviceMetricsOverride', 'Emulation.clearDeviceMetricsOverride'
]);
