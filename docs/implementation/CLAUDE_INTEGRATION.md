# Claude Code Integration

## Register the MCP server
```bash
mvn install -DskipTests          # builds mcp-server/target/cdb-mcp-server.jar and the driver
claude mcp add chromium-bridge -- java -jar /ABS/PATH/mcp-server/target/cdb-mcp-server.jar \
    --driver-jar /ABS/PATH/browser-driver/target/cdb-driver-exec.jar
```
or commit a project-scoped `.mcp.json` (template: `scripts/mcp/claude-code.mcp.json`). With `--driver-jar`, the MCP
server starts the driver if it isn't running. Without it, start the driver yourself or from the IDE.

Check with `claude mcp list` or `/mcp` inside Claude Code: `chromium-bridge` should list 17 tools.

## Walkthrough: debugging the broken login
```bash
DEMO_BUG=login node sample-applications/login-demo/server.mjs
```
Prompt:
> Open http://localhost:3000/login in the browser bridge, investigate why the login button is not working, inspect
> the console and failed API requests, find the relevant source code, fix it and verify the login flow using the test
> credentials (TEST_EMAIL / TEST_PASSWORD).

Expected tool sequence: `browser_create_session` → `browser_snapshot(interactive)` → `browser_type` ×2 →
`browser_click` → `browser_network_logs(failedOnly)` reveals `POST /api/signin -> 404` → `browser_console_logs` shows
the missing token → the agent edits `public/js/login-buggy.js` (or switches to `/api/login`) → reload →
`browser_test_flow` with `scripts/scenarios/login-validation.json` reports PASSED.

For `${TEST_EMAIL}`/`${TEST_PASSWORD}`, set those environment variables for the **driver** process (only `TEST_*` and
`CDB_TEST_*` variables are visible to scenarios), e.g. `TEST_EMAIL=qa@example.test TEST_PASSWORD=demo-password scripts/linux/start-driver.sh`.

## Tips
* Use `mode: "interactive"` snapshots for clicking and `compact` to read page structure; refs (`element-N`) change after
  navigation, so take a new snapshot afterwards.
* Use `afterSeq` with console/network logs to fetch only what's new after an action.
* Ask the user to approve non-development origins in the IDE's Sessions tab; the agent can't do it.
