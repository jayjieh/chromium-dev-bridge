package dev.cdb.it;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;

/** Starts sample-applications/login-demo with Node on a random port. */
final class SampleApp implements AutoCloseable {
    private static final Pattern LISTENING = Pattern.compile("listening on (http://\\S+)");
    final Process process;
    final String url;

    private SampleApp(Process process, String url) {
        this.process = process;
        this.url = url;
    }

    static SampleApp start() throws Exception {
        Path server = Path.of(System.getProperty("cdb.sampleApp", "../sample-applications/login-demo/server.mjs"));
        Assumptions.assumeTrue(Files.isRegularFile(server), "sample app not found");
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder("node", server.toString()).redirectErrorStream(true);
            pb.environment().put("PORT", "0");
            p = pb.start();
        } catch (Exception e) {
            Assumptions.abort("Node.js is required for integration tests: " + e.getMessage());
            throw e;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 15_000;
        String line;
        while (System.currentTimeMillis() < deadline && (line = r.readLine()) != null) {
            Matcher m = LISTENING.matcher(line);
            if (m.find()) return new SampleApp(p, m.group(1));
        }
        p.destroyForcibly();
        throw new IllegalStateException("Sample app did not start");
    }

    @Override
    public void close() throws Exception {
        process.destroy();
        process.waitFor(5, TimeUnit.SECONDS);
    }
}
