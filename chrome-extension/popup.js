import { isDevUrl, isRestrictedUrl } from './common.js';

const $ = (id) => document.getElementById(id);

async function call(type, extra = {}) {
  const res = await chrome.runtime.sendMessage({ type, ...extra });
  if (!res.ok) throw new Error(res.error);
  return res.result;
}

function showError(e) {
  $('error').hidden = !e;
  $('error').textContent = e ? String(e.message || e) : '';
}

function render(s) {
  $('dot').className = 'dot ' + s.state;
  $('state').textContent = { connected: 'Connected to ' + s.driver, connecting: 'Connecting…', disconnected: 'Driver not reachable',
    'not-paired': 'Not paired' }[s.state] || s.state;
  $('pairing').hidden = s.paired;
  $('tab').hidden = !s.paired || !s.currentTab;
  if (s.currentTab) {
    const t = s.currentTab;
    $('tab-title').textContent = t.title || '(untitled)';
    $('tab-url').textContent = t.url || '';
    const isShared = !!t.shared;
    $('share-options').hidden = isShared;
    $('shared-info').hidden = !isShared;
    $('session').textContent = isShared ? (t.shared.sessionId || 'connecting…') : '';
    $('approve-row').hidden = isShared || isDevUrl(t.url);
    const restricted = isRestrictedUrl(t.url);
    $('share').disabled = restricted || s.state !== 'connected';
    $('share').textContent = restricted ? 'This page can’t be shared' : 'Share this tab';
  }
  $('list').hidden = !s.shared.length;
  const ul = $('shared');
  ul.replaceChildren(...s.shared.map(t => {
    const li = document.createElement('li');
    const span = document.createElement('span');
    span.textContent = (t.title || t.url || 'tab ' + t.id) + (t.sessionId ? ' — ' + t.sessionId : '');
    const b = document.createElement('button');
    b.className = 'secondary';
    b.textContent = 'Stop';
    b.onclick = () => run(() => call('unshare', { tabId: t.id }));
    li.append(span, b);
    return li;
  }));
  if (s.lastError && s.state !== 'connected') showError(s.lastError);
}

async function run(fn) {
  showError(null);
  try {
    render(await fn());
  } catch (e) {
    showError(e);
    render(await call('status'));
  }
}

$('pair').onclick = () => run(() => call('pair', { code: $('code').value }));
$('share').onclick = () => run(async () => {
  const s = await call('status');
  return call('share', { tabId: s.currentTab.id, options: { agentAccess: $('agents').checked, approveOrigin: $('approve').checked } });
});
$('unshare').onclick = () => run(async () => call('unshare', { tabId: (await call('status')).currentTab.id }));
$('repair').onclick = (e) => { e.preventDefault(); $('pairing').hidden = false; };
$('reconnect').onclick = (e) => { e.preventDefault(); run(() => call('reconnect')); };

run(() => call('status'));
setInterval(() => call('status').then(render).catch(() => {}), 1500);
