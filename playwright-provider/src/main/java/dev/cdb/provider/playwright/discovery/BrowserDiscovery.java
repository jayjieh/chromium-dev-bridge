package dev.cdb.provider.playwright.discovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Finds Chromium-based browsers installed on Windows, macOS and Linux (for diagnostics and the channel option). */
public final class BrowserDiscovery {
    public record DiscoveredBrowser(String kind, Path path) { }

    private BrowserDiscovery() {
    }

    public static List<DiscoveredBrowser> discover() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        List<DiscoveredBrowser> out = new ArrayList<>();
        if (os.contains("win")) {
            for (String base : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")}) {
                if (base == null) continue;
                add(out, "chrome", Path.of(base, "Google", "Chrome", "Application", "chrome.exe"));
                add(out, "msedge", Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe"));
                add(out, "chromium", Path.of(base, "Chromium", "Application", "chrome.exe"));
            }
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) playwrightCache(out, Path.of(local, "ms-playwright"));
        } else if (os.contains("mac")) {
            add(out, "chrome", Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            add(out, "msedge", Path.of("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
            add(out, "chromium", Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"));
            playwrightCache(out, Path.of(home, "Library", "Caches", "ms-playwright"));
        } else {
            for (String p : new String[]{"/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/opt/google/chrome/chrome"}) {
                add(out, "chrome", Path.of(p));
            }
            for (String p : new String[]{"/usr/bin/chromium", "/usr/bin/chromium-browser", "/snap/bin/chromium"}) {
                add(out, "chromium", Path.of(p));
            }
            add(out, "msedge", Path.of("/usr/bin/microsoft-edge"));
            playwrightCache(out, Path.of(home, ".cache", "ms-playwright"));
        }
        return out;
    }

    private static void add(List<DiscoveredBrowser> out, String kind, Path p) {
        if (Files.isRegularFile(p) && out.stream().noneMatch(b -> b.path().equals(p))) out.add(new DiscoveredBrowser(kind, p));
    }

    private static void playwrightCache(List<DiscoveredBrowser> out, Path cache) {
        if (!Files.isDirectory(cache)) return;
        try (Stream<Path> dirs = Files.list(cache)) {
            dirs.filter(d -> d.getFileName().toString().startsWith("chromium-")).forEach(d ->
                    out.add(new DiscoveredBrowser("playwright-chromium", d)));
        } catch (Exception ignored) {
            // unreadable cache
        }
    }
}
