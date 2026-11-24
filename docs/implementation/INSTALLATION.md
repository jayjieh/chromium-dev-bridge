# Installation

## Requirements
| Component | Version |
|---|---|
| JDK | 21+ (Temurin, Oracle, Corretto) |
| Maven | 3.8+ |
| Node.js | 18+ (sample app and end-to-end tests only) |
| IntelliJ IDEA | 2025.3+ (plugin) |
| Disk | ~400 MB for Playwright's Chromium (downloaded on first session) |

## Windows (verified on Windows 11, JDK 21, Maven 3.8.1)
```powershell
git clone <repo> chromium-dev-bridge; cd chromium-dev-bridge
mvn install -DskipTests
.\scripts\windows\start-driver.ps1                 # add --cdb.browser.channel=msedge to use Edge
node sample-applications\login-demo\server.mjs     # optional sample app
```
Check: `Invoke-RestMethod http://127.0.0.1:7878/api/v1/health` returns `status: UP`.

## macOS
```bash
brew install openjdk@21 maven node
mvn install -DskipTests
scripts/macos/start-driver.sh
```

## Linux
```bash
sudo apt install openjdk-21-jdk maven nodejs
mvn install -DskipTests
# Chromium system libraries (once):
sudo npx -y playwright@1.65.1 install-deps chromium
scripts/linux/start-driver.sh
```

## Configuration
All settings live under `cdb.*` (see `browser-driver/src/main/resources/application.yml`). Override with
command-line flags (`--cdb.sessions.max=5`), environment variables (`CDB_SESSIONS_MAX=5`, `CDB_PORT=7979`), or an
`application.yml` next to the jar. Frequently used:

| Property | Default | Purpose |
|---|---|---|
| `cdb.browser.channel` | — | `chrome` / `msedge` to use an installed browser |
| `cdb.browser.executable-path` | — | Explicit Chromium executable |
| `cdb.browser.headless` | `true` | Default for new sessions |
| `cdb.security.allowed-hosts` | dev hosts | Add e.g. `myapp.internal:8443` |
| `cdb.capture.bodies` | `false` | Capture (redacted) request/response bodies |
| `management.endpoints.web.exposure.include` | `health,info,metrics` | Add `prometheus` for `/actuator/prometheus` |
| `logging.structured.format.console` | — | `ecs` or `logstash` for JSON logs |

Data directory: `~/.chromium-dev-bridge` (`driver.json`, `audit/`, `screenshots/`, `baselines/`, `profiles/`, `logs/`).

## IntelliJ plugin
```bash
cd intellij-plugin && ./gradlew buildPlugin
```
Install `intellij-plugin/build/distributions/chromium-dev-bridge-intellij-0.1.0.zip` via *Settings | Plugins | ⚙ |
Install Plugin from Disk*. Then set *Settings | Tools | Chromium Developer Bridge | Driver jar* to
`browser-driver/target/cdb-driver-exec.jar` so the IDE can start the driver for you.

## Upgrading
Stop the driver, pull, run `mvn install -DskipTests`, start again. Tokens and connection details are regenerated on
each start; MCP clients and the IDE pick them up from `driver.json`.

## Troubleshooting
| Symptom | Fix |
|---|---|
| `Chromium installation failed` | Offline/proxy: set `cdb.browser.channel=chrome` or `executable-path` |
| Linux: browser fails to launch | Install system deps (see above) |
| `401 UNAUTHENTICATED` | Client uses a stale token: restart the client so it re-reads `driver.json` |
| `URL_NOT_ALLOWED` | Add the host to `cdb.security.allowed-hosts` or approve the origin in the IDE Sessions tab |
| Port in use | `CDB_PORT=7979` |
