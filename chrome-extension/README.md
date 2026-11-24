# Chromium Developer Bridge — Chrome extension

Shares tabs from your everyday Chrome with the Chromium Developer Bridge driver, so the IntelliJ plugin and AI agents
(MCP) can inspect and drive them. See `docs/implementation/CHROME_EXTENSION.md` for the full guide.

1. `chrome://extensions` → enable *Developer mode* → *Load unpacked* → select this folder.
2. Start the driver, then copy the pairing code (IntelliJ: *Chromium Bridge › Settings › Copy Chrome pairing code*, or
   `scripts/windows/pairing-code.ps1` / `scripts/linux/pairing-code.sh`) and paste it into the extension popup.
3. Open your app (e.g. `http://localhost:3000`), click the extension icon → *Share this tab*.

Chrome shows "Chromium Developer Bridge started debugging this browser" while a tab is shared. Click *Cancel* on that
bar or *Stop sharing* in the popup to end it.
