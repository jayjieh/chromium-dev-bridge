package dev.cdb.core.visual;

import dev.cdb.core.BridgeException;
import dev.cdb.core.Clock;
import dev.cdb.protocol.BaselineInfo;
import dev.cdb.protocol.ErrorCode;
import dev.cdb.protocol.ImageFormat;
import dev.cdb.protocol.ScreenshotInfo;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local screenshot history and baselines with retention. */
public class ScreenshotStore {
    private static final Logger log = LoggerFactory.getLogger(ScreenshotStore.class);
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    public record Stored(ScreenshotInfo info, Path file, String owner) { }

    private final Path screenshotsDir;
    private final Path baselinesDir;
    private final int maxPerSession;
    private final Duration maxAge;
    private final Clock clock;
    private final Map<String, Stored> byId = new ConcurrentHashMap<>();

    public ScreenshotStore(Path screenshotsDir, Path baselinesDir, int maxPerSession, Duration maxAge, Clock clock) {
        this.screenshotsDir = screenshotsDir;
        this.baselinesDir = baselinesDir;
        this.maxPerSession = maxPerSession;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    public Stored save(String sessionId, String owner, String name, byte[] bytes, ImageFormat format, String pageUrl) {
        try {
            BufferedImage img = ImageDiff.read(bytes);
            String id = "shot-" + java.util.UUID.randomUUID().toString().substring(0, 12);
            Path dir = screenshotsDir.resolve(sessionId);
            Files.createDirectories(dir);
            Path file = dir.resolve(id + (format == ImageFormat.JPEG ? ".jpg" : ".png"));
            Files.write(file, bytes);
            ScreenshotInfo info = new ScreenshotInfo(id, sessionId, name, format, img.getWidth(), img.getHeight(),
                    bytes.length, clock.now(), pageUrl, "/api/v1/screenshots/" + id + "/image", null);
            Stored stored = new Stored(info, file, owner);
            byId.put(id, stored);
            enforceRetention(sessionId);
            return stored;
        } catch (IOException e) {
            throw new BridgeException(ErrorCode.INTERNAL, "Could not store screenshot: " + e.getMessage(), e);
        }
    }

    public Stored get(String screenshotId) {
        Stored s = byId.get(screenshotId);
        if (s == null || !Files.exists(s.file())) {
            throw new BridgeException(ErrorCode.NOT_FOUND, "Screenshot not found: " + screenshotId);
        }
        return s;
    }

    public byte[] bytes(String screenshotId) {
        try {
            return Files.readAllBytes(get(screenshotId).file());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<ScreenshotInfo> list(String sessionId) {
        return byId.values().stream().filter(s -> s.info().sessionId().equals(sessionId))
                .map(Stored::info).sorted(Comparator.comparing(ScreenshotInfo::createdAt).reversed()).toList();
    }

    public BaselineInfo saveBaseline(String name, String screenshotId) {
        requireSafeName(name);
        Stored s = get(screenshotId);
        try {
            Files.createDirectories(baselinesDir);
            byte[] png = s.info().format() == ImageFormat.PNG ? Files.readAllBytes(s.file()) : toPng(Files.readAllBytes(s.file()));
            Files.write(baselinesDir.resolve(name + ".png"), png);
            return new BaselineInfo(name, s.info().width(), s.info().height(), clock.now());
        } catch (IOException e) {
            throw new BridgeException(ErrorCode.INTERNAL, "Could not store baseline: " + e.getMessage(), e);
        }
    }

    public byte[] baseline(String name) {
        requireSafeName(name);
        Path file = baselinesDir.resolve(name + ".png");
        if (!Files.exists(file)) throw new BridgeException(ErrorCode.NOT_FOUND, "Baseline not found: " + name);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean hasBaseline(String name) {
        requireSafeName(name);
        return Files.exists(baselinesDir.resolve(name + ".png"));
    }

    public List<BaselineInfo> baselines() {
        if (!Files.isDirectory(baselinesDir)) return List.of();
        List<BaselineInfo> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(baselinesDir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".png")).toList()) {
                BufferedImage img = ImageDiff.read(Files.readAllBytes(p));
                String n = p.getFileName().toString();
                out.add(new BaselineInfo(n.substring(0, n.length() - 4), img.getWidth(), img.getHeight(),
                        Files.getLastModifiedTime(p).toInstant()));
            }
        } catch (IOException e) {
            log.warn("Could not list baselines: {}", e.toString());
        }
        out.sort(Comparator.comparing(BaselineInfo::name));
        return out;
    }

    /** Removes screenshots of a closed session from the in-memory index (files stay until age retention). */
    public void forgetSession(String sessionId) {
        byId.values().removeIf(s -> s.info().sessionId().equals(sessionId) && !Files.exists(s.file()));
    }

    /** Deletes files older than maxAge and trims every session to maxPerSession. */
    public void enforceRetention() {
        Instant cutoff = clock.now().minus(maxAge);
        byId.values().removeIf(s -> {
            if (s.info().createdAt().isBefore(cutoff)) {
                delete(s.file());
                return true;
            }
            return false;
        });
        if (Files.isDirectory(screenshotsDir)) {
            try (Stream<Path> walk = Files.walk(screenshotsDir, 2)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) delete(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            } catch (IOException e) {
                log.debug("Retention walk failed: {}", e.toString());
            }
        }
    }

    private void enforceRetention(String sessionId) {
        List<Stored> session = byId.values().stream().filter(s -> s.info().sessionId().equals(sessionId))
                .sorted(Comparator.comparing((Stored s) -> s.info().createdAt())).toList();
        int excess = session.size() - maxPerSession;
        for (int i = 0; i < excess; i++) {
            Stored s = session.get(i);
            byId.remove(s.info().screenshotId());
            delete(s.file());
        }
    }

    private static void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("Could not delete {}: {}", p, e.toString());
        }
    }

    private static byte[] toPng(byte[] bytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(ImageDiff.read(bytes), "png", out);
        return out.toByteArray();
    }

    static void requireSafeName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw BridgeException.validation("Name must match [A-Za-z0-9._-]{1,100}");
        }
    }
}
