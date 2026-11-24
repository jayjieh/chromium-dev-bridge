"use strict";
/* compiled from src/dashboard.ts */
// Original TypeScript source of public/js/dashboard.js (see dashboard.js.map).


async function loadStats() {
  const token = sessionStorage.getItem('token');
  const response = await fetch('/api/stats', { headers: { Authorization: 'Bearer ' + token } });
  if (!response.ok) { console.error('Failed to load statistics: ' + response.status); return; }
  const stats = await response.json();
  document.querySelector('[data-testid=visitors]').textContent = String(stats.visitors);
  document.querySelector('[data-testid=conversions]').textContent = String(stats.conversions);
  document.querySelector('[data-testid=revenue]').textContent = '$' + stats.revenue;
}

async function loadNotifications() {
  const response = await fetch('/api/notifications');
  if (!response.ok) {
    const banner = document.getElementById('notification-error');
    banner.textContent = 'Could not load notifications';
    banner.hidden = false;
    console.error('Failed to load application data');
    return;
  }
}

function renderWidgets(config) {
  // Deliberate bug: config.widgets is undefined.
  config.widgets.forEach((w) => console.log(w));
}

loadStats();
loadNotifications();
setTimeout(() => renderWidgets({}), 50);
//# sourceMappingURL=dashboard.js.map
