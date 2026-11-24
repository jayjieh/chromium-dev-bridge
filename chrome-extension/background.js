// Chromium Developer Bridge — service worker.
// Keeps a WebSocket to the local driver and relays CDP for tabs the user explicitly shared.
import { parsePairing, isRestrictedUrl, ALLOWED_COMMANDS } from './common.js';

const PROTOCOL = 'cdb-ext';
const VERSION = chrome.runtime.getManifest().version;
const DEBUGGER_VERSION = '1.3';

let ws = null;
let state = 'disconnected'; // not-paired | connecting | connected | disconnected
let lastError = null;
let nextId = 1;
let backoff = 1000;
let keepAlive = null;
const pending = new Map();
/** tabId -> { sessionId, agentAccess, approveOrigin } */
const shared = new Map();

// ---------------------------------------------------------------- persistence (survives service worker restarts)

async function loadShared() {
  const { sharedTabs } = await chrome.storage.session.get('sharedTabs');
  shared.clear();
  for (const [id, v] of Object.entries(sharedTabs || {})) shared.set(Number(id), v);
}

async function saveShared() {
  await chrome.storage.session.set({ sharedTabs: Object.fromEntries(shared) });
}

async function pairing() {
  const { pairing } = await chrome.storage.local.get('pairing');
  return parsePairing(pairing);
}

// ---------------------------------------------------------------- connection

async function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  const cfg = await pairing();
  if (!cfg) {
    state = 'not-paired';
    return;
  }
  state = 'connecting';
  const socket = new WebSocket(`ws://${cfg.host}:${cfg.port}/api/v1/extension`, [PROTOCOL, cfg.token]);
  ws = socket;
  socket.onopen = async () => {
    state = 'connected';
    lastError = null;
    backoff = 1000;
    clearInterval(keepAlive);
    // WebSocket traffic keeps the MV3 service worker alive (Chrome 116+).
    keepAlive = setInterval(() => request('ping', {}).catch(() => {}), 20000);
    try {
      await request('hello', { version: VERSION, userAgent: navigator.userAgent });
      await loadShared();
      for (const tabId of shared.keys()) await announce(tabId).catch(e => console.warn('re-announce failed', e));
    } catch (e) {
      lastError = String(e.message || e);
    }
  };
  socket.onmessage = (event) => handle(event.data);
  socket.onerror = () => { lastError = 'Cannot reach the driver at ' + cfg.host + ':' + cfg.port; };
  socket.onclose = () => {
    if (ws !== socket) return;
    ws = null;
    state = 'disconnected';
    clearInterval(keepAlive);
    for (const p of pending.values()) p.reject(new Error('Driver connection closed'));
    pending.clear();
    for (const v of shared.values()) v.sessionId = null;
    setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 30000);
  };
}

function send(msg) {
  if (!ws || ws.readyState !== WebSocket.OPEN) throw new Error('Not connected to the driver');
  ws.send(JSON.stringify(msg));
}

function request(method, params) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    try {
      send({ jsonrpc: '2.0', id, method, params });
    } catch (e) {
      pending.delete(id);
      reject(e);
    }
  });
}

function notify(method, params) {
  try {
    send({ jsonrpc: '2.0', method, params });
  } catch {
    // not connected; state is re-announced on reconnect
  }
}

function reply(id, result) {
  try { send({ jsonrpc: '2.0', id, result }); } catch { /* closed */ }
}

function replyError(id, message) {
  try { send({ jsonrpc: '2.0', id, error: { code: -32000, message } }); } catch { /* closed */ }
}

async function handle(text) {
  let msg;
  try {
    msg = JSON.parse(text);
  } catch {
    return;
  }
  if (!msg.method) {
    const p = pending.get(msg.id);
    if (p) {
      pending.delete(msg.id);
      if (msg.error) p.reject(new Error(msg.error.message)); else p.resolve(msg.result);
    }
    return;
  }
  const params = msg.params || {};
  try {
    switch (msg.method) {
      case 'cdp.send': {
        if (!shared.has(params.tabId)) throw new Error('Tab is not shared');
        if (!ALLOWED_COMMANDS.has(params.method)) throw new Error('CDP command not allowed by the extension: ' + params.method);
        const result = await chrome.debugger.sendCommand({ tabId: params.tabId }, params.method, params.params || {});
        reply(msg.id, result || {});
        break;
      }
      case 'tab.reattach': {
        if (!shared.has(params.tabId)) throw new Error('Tab is not shared');
        try { await chrome.debugger.detach({ tabId: params.tabId }); } catch { /* not attached */ }
        await chrome.debugger.attach({ tabId: params.tabId }, DEBUGGER_VERSION);
        reply(msg.id, {});
        break;
      }
      case 'tab.activate': {
        if (!shared.has(params.tabId)) throw new Error('Tab is not shared');
        const tab = await chrome.tabs.update(params.tabId, { active: true });
        await chrome.windows.update(tab.windowId, { focused: true });
        reply(msg.id, {});
        break;
      }
      case 'tab.unshare':
        await unshare(params.tabId, 'closed by the driver', false);
        break;
      case 'session.attached': {
        const v = shared.get(params.tabId);
        if (v) {
          v.sessionId = params.sessionId;
          await saveShared();
        }
        break;
      }
      default:
        if (msg.id != null) replyError(msg.id, 'Unknown method ' + msg.method);
    }
  } catch (e) {
    if (msg.id != null) replyError(msg.id, String(e.message || e));
  }
}

// ---------------------------------------------------------------- sharing

async function announce(tabId) {
  const v = shared.get(tabId);
  const tab = await chrome.tabs.get(tabId);
  try {
    await chrome.debugger.attach({ tabId }, DEBUGGER_VERSION);
  } catch (e) {
    if (!String(e.message).includes('already attached')) throw e;
  }
  const result = await request('tab.shared', {
    tabId, url: tab.url, title: tab.title, agentAccess: v.agentAccess, approveOrigin: v.approveOrigin
  });
  v.sessionId = result.sessionId;
  await saveShared();
  return result.sessionId;
}

async function share(tabId, { agentAccess = true, approveOrigin = false } = {}) {
  const tab = await chrome.tabs.get(tabId);
  if (isRestrictedUrl(tab.url)) throw new Error('Chrome does not allow sharing this kind of page');
  if (state !== 'connected') throw new Error(state === 'not-paired' ? 'Pair with the driver first' : 'Not connected to the driver');
  shared.set(tabId, { sessionId: null, agentAccess, approveOrigin });
  await saveShared();
  try {
    const sessionId = await announce(tabId);
    await chrome.action.setBadgeText({ tabId, text: 'ON' });
    await chrome.action.setBadgeBackgroundColor({ tabId, color: '#3355ff' });
    return sessionId;
  } catch (e) {
    shared.delete(tabId);
    await saveShared();
    try { await chrome.debugger.detach({ tabId }); } catch { /* not attached */ }
    throw e;
  }
}

async function unshare(tabId, reason, tellDriver = true) {
  if (!shared.has(tabId)) return;
  shared.delete(tabId);
  await saveShared();
  try { await chrome.debugger.detach({ tabId }); } catch { /* already detached */ }
  try { await chrome.action.setBadgeText({ tabId, text: '' }); } catch { /* tab closed */ }
  if (tellDriver) notify('tab.unshared', { tabId, reason });
}

chrome.debugger.onEvent.addListener((source, method, params) => {
  if (shared.has(source.tabId)) notify('cdp.event', { tabId: source.tabId, method, params });
});

chrome.debugger.onDetach.addListener(async (source, reason) => {
  // e.g. the user clicked "Cancel" on Chrome's debugging infobar, or opened DevTools-exclusive tooling.
  if (!shared.has(source.tabId)) return;
  shared.delete(source.tabId);
  await saveShared();
  notify('tab.unshared', { tabId: source.tabId, reason });
});

chrome.tabs.onRemoved.addListener((tabId) => unshare(tabId, 'tab closed'));

chrome.tabs.onUpdated.addListener((tabId, change) => {
  if (shared.has(tabId) && (change.url || change.title)) notify('tab.updated', { tabId, url: change.url, title: change.title });
});

// ---------------------------------------------------------------- popup API

async function status() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const cfg = await pairing();
  return {
    state: cfg ? state : 'not-paired', lastError, paired: !!cfg, driver: cfg ? cfg.host + ':' + cfg.port : null,
    currentTab: tab ? { id: tab.id, url: tab.url, title: tab.title, shared: shared.get(tab.id) || null } : null,
    shared: await Promise.all([...shared.entries()].map(async ([id, v]) => {
      try { const t = await chrome.tabs.get(id); return { id, title: t.title, url: t.url, ...v }; } catch { return { id, ...v }; }
    }))
  };
}

async function pair(code) {
  if (!parsePairing(code)) throw new Error('Invalid pairing code. Expected 127.0.0.1:<port>#<token>');
  await chrome.storage.local.set({ pairing: code.trim() });
  if (ws) ws.close();
  ws = null;
  await connect();
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  (async () => {
    switch (msg.type) {
      case 'status': return status();
      case 'pair': await pair(msg.code); return status();
      case 'share': await share(msg.tabId, msg.options); return status();
      case 'unshare': await unshare(msg.tabId, 'stopped by the user'); return status();
      case 'reconnect': if (ws) ws.close(); else await connect(); return status();
      default: throw new Error('Unknown request');
    }
  })().then(r => sendResponse({ ok: true, result: r }), e => sendResponse({ ok: false, error: String(e.message || e) }));
  return true;
});

// Reconnect after browser start or service worker restart.
chrome.alarms.create('cdb-reconnect', { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener((a) => { if (a.name === 'cdb-reconnect') connect(); });
chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(connect);
loadShared().then(connect);

// Test hook (used by the end-to-end test through the service worker).
globalThis.cdb = { share, unshare, pair, status, connect };
