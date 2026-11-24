# login-demo — sample application

A dependency-free web app used to exercise every browser driver capability. Run it with Node 18+:

```bash
node sample-applications/login-demo/server.mjs          # http://127.0.0.1:3000
PORT=0 node sample-applications/login-demo/server.mjs   # random free port (printed on stdout)
```

| Page | What it exercises |
|---|---|
| `/login` | Form with labelled email, `data-testid="password"` field, client-side validation, `POST /api/login` |
| `/dashboard` | Loads `/api/stats` (200) and `/api/notifications` (**deliberate 500**); throws a **runtime error** from `js/dashboard.js`, which has a source map to `src/dashboard.ts` |
| `/register` | Form validation messages (required, email format, password length, terms checkbox, country select) |
| `/a11y` | Accessibility test elements: image without alt, unlabeled input, skipped heading level, good examples |
| `/injection` | Page text that tries to instruct an AI agent (prompt-injection safeguard test) |

Layout is responsive (navigation collapses below 768 px).

**Test credentials** (test-only, not secrets): `qa@example.test` / `demo-password`. Override with `TEST_EMAIL` and
`TEST_PASSWORD` environment variables when starting the server.

Set `DEMO_BUG=login` to serve a login script with a deliberate bug (it posts to a non-existent endpoint), for the
AI-assisted debugging walkthrough in `docs/implementation/CLAUDE_INTEGRATION.md`.
