package dev.cdb.core.console;

import dev.cdb.protocol.SourceLocation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps compiled script locations back to original sources using source maps served by the dev server.
 * Only fetches from URLs accepted by the supplied predicate (development hosts).
 */
public class SourceMapResolver {
    private static final Logger log = LoggerFactory.getLogger(SourceMapResolver.class);
    private static final Pattern MAP_COMMENT = Pattern.compile("//[#@]\\s*sourceMappingURL=(\\S+)\\s*$", Pattern.MULTILINE);
    private static final int MAX_SCRIPT_BYTES = 20 * 1024 * 1024;

    private final Function<String, Optional<String>> fetcher;
    private final Predicate<String> urlAllowed;
    private final Map<String, Optional<SourceMap>> cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<SourceMap>> e) {
            return size() > 64;
        }
    });

    public SourceMapResolver(Function<String, Optional<String>> fetcher, Predicate<String> urlAllowed) {
        this.fetcher = fetcher;
        this.urlAllowed = urlAllowed;
    }

    public static SourceMapResolver withHttp(Predicate<String> urlAllowed) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new SourceMapResolver(url -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
                HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200 || resp.body().length > MAX_SCRIPT_BYTES) return Optional.empty();
                return Optional.of(new String(resp.body(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return Optional.empty();
            }
        }, urlAllowed);
    }

    public Optional<SourceLocation> resolve(SourceLocation generated) {
        if (generated == null || generated.url() == null || generated.line() == null) return Optional.empty();
        String scriptUrl = generated.url();
        if (!scriptUrl.startsWith("http://") && !scriptUrl.startsWith("https://")) return Optional.empty();
        if (!urlAllowed.test(scriptUrl)) return Optional.empty();
        Optional<SourceMap> map = cache.computeIfAbsent(stripQuery(scriptUrl), this::load);
        return map.map(m -> m.lookup(generated.line(), generated.column() == null ? 1 : generated.column()))
                .map(o -> new SourceLocation(o.source(), SourceMap.normalizeSourcePath(o.source()), o.line(), o.column()));
    }

    private Optional<SourceMap> load(String scriptUrl) {
        try {
            Optional<String> script = fetcher.apply(scriptUrl);
            if (script.isEmpty()) return Optional.empty();
            Matcher m = MAP_COMMENT.matcher(script.get());
            String ref = null;
            while (m.find()) ref = m.group(1);
            if (ref == null) return Optional.empty();
            String json;
            if (ref.startsWith("data:")) {
                int comma = ref.indexOf(',');
                String payload = ref.substring(comma + 1);
                json = ref.substring(0, comma).contains(";base64")
                        ? new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
                        : java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8);
            } else {
                String mapUrl = URI.create(scriptUrl).resolve(ref).toString();
                if (!urlAllowed.test(mapUrl)) return Optional.empty();
                Optional<String> fetched = fetcher.apply(mapUrl);
                if (fetched.isEmpty()) return Optional.empty();
                json = fetched.get();
            }
            return Optional.of(SourceMap.parse(json));
        } catch (Exception e) {
            log.debug("Source map unavailable for {}: {}", scriptUrl, e.toString());
            return Optional.empty();
        }
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
