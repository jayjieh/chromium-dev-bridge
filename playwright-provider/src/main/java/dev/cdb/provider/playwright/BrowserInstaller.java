package dev.cdb.provider.playwright;

import com.microsoft.playwright.impl.driver.Driver;
import dev.cdb.core.BridgeException;
import dev.cdb.protocol.ErrorCode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Installs only Playwright's Chromium (not Firefox/WebKit) using the bundled Playwright CLI. */
public final class BrowserInstaller {
    private static final Logger log = LoggerFactory.getLogger(BrowserInstaller.class);

    private BrowserInstaller() {
    }

    public static void installChromium() {
        try {
            Driver driver = Driver.ensureDriverInstalled(Collections.emptyMap(), false);
            ProcessBuilder pb = driver.createProcessBuilder();
            pb.command().add("install");
            pb.command().add("chromium");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank() && !line.contains("|")) log.info("[playwright install] {}", line.trim());
                }
            }
            if (!p.waitFor(15, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new BridgeException(ErrorCode.BROWSER_ERROR, "Chromium installation failed (exit "
                        + (p.isAlive() ? "timeout" : p.exitValue()) + "). Set cdb.browser.executable-path or cdb.browser.channel.");
            }
        } catch (BridgeException e) {
            throw e;
        } catch (Exception e) {
            throw new BridgeException(ErrorCode.BROWSER_ERROR, "Chromium installation failed: " + e.getMessage()
                    + ". Set cdb.browser.executable-path or cdb.browser.channel=chrome.", e);
        }
    }
}
