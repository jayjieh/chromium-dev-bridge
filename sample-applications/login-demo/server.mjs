// Zero-dependency sample server for Chromium Developer Bridge tests and demos.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('./public/', import.meta.url));
const srcRoot = fileURLToPath(new URL('./src/', import.meta.url));
const port = Number(process.env.PORT ?? 3000);
const email = process.env.TEST_EMAIL ?? 'qa@example.test';
const password = process.env.TEST_PASSWORD ?? 'demo-password';
const bug = process.env.DEMO_BUG ?? '';
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.map': 'application/json',
  '.ts': 'text/plain', '.svg': 'image/svg+xml', '.png': 'image/png' };
const pages = { '/': 'login.html', '/login': 'login.html', '/dashboard': 'dashboard.html', '/register': 'register.html',
  '/a11y': 'a11y.html', '/injection': 'injection.html' };

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

async function body(req) {
  let data = '';
  for await (const chunk of req) {
    data += chunk;
    if (data.length > 10_000) throw new Error('too large');
  }
  return data ? JSON.parse(data) : {};
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  try {
    if (url.pathname === '/api/login' && req.method === 'POST') {
      const b = await body(req);
      if (b.email === email && b.password === password) return json(res, 200, { token: 'demo-session-token', user: { email } });
      return json(res, 401, { error: 'Invalid email or password' });
    }
    if (url.pathname === '/api/stats') {
      if (req.headers.authorization !== 'Bearer demo-session-token') return json(res, 401, { error: 'Not signed in' });
      return json(res, 200, { visitors: 1284, conversions: 37, revenue: 4210 });
    }
    if (url.pathname === '/api/notifications') {
      return json(res, 500, { error: 'Notification service unavailable', code: 'NOTIFY_DOWN' });
    }
    if (url.pathname === '/api/register' && req.method === 'POST') {
      const b = await body(req);
      return json(res, 201, { id: 42, email: b.email });
    }
    let file = pages[url.pathname];
    let base = root;
    if (!file) {
      file = normalize(url.pathname).replace(/^([/\\])+/, '');
      if (file.startsWith('src')) { base = srcRoot; file = file.slice(4); }
    }
    if (file.includes('..')) return json(res, 400, { error: 'bad path' });
    if (bug === 'login' && file === join('js', 'login.js')) file = join('js', 'login-buggy.js');
    const content = await readFile(join(base, file));
    res.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' });
    res.end(content);
  } catch (e) {
    if (e.code === 'ENOENT') return json(res, 404, { error: 'Not found' });
    json(res, 500, { error: String(e.message ?? e) });
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`login-demo listening on http://127.0.0.1:${server.address().port}`);
});
