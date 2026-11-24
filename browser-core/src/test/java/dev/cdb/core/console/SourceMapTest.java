package dev.cdb.core.console;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cdb.protocol.SourceLocation;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceMapTest {
    // Generated line 1 -> src/dashboard.tsx line 1 col 1; generated line 2 col 5 -> line 42 col 12 (0-based 41/11).
    static final String MAP = """
            {"version":3,"sources":["webpack://app/./src/dashboard.tsx"],"names":[],
             "mappings":"AAAA;IAyCW"}
            """;

    @Test
    void decodesMappings() {
        SourceMap map = SourceMap.parse(MAP);
        SourceMap.Original o = map.lookup(2, 6);
        assertThat(o.source()).isEqualTo("webpack://app/./src/dashboard.tsx");
        assertThat(o.line()).isEqualTo(42);
        assertThat(o.column()).isEqualTo(12);
        assertThat(map.lookup(1, 1).line()).isEqualTo(1);
        assertThat(map.lookup(99, 1)).isNull();
    }

    @Test
    void normalizesSourcePaths() {
        assertThat(SourceMap.normalizeSourcePath("webpack://app/./src/dashboard.tsx")).isEqualTo("src/dashboard.tsx");
        assertThat(SourceMap.normalizeSourcePath("../src/app.ts")).isEqualTo("src/app.ts");
        assertThat(SourceMap.normalizeSourcePath("http://localhost:5173/src/main.ts")).isEqualTo("src/main.ts");
    }

    @Test
    void resolvesExternalAndInlineMaps() {
        Map<String, String> files = Map.of(
                "http://localhost:3000/main.js", "console.log(1)\n  x()\n//# sourceMappingURL=main.js.map",
                "http://localhost:3000/main.js.map", MAP,
                "http://localhost:3000/inline.js", "a\nb\n//# sourceMappingURL=data:application/json;base64,"
                        + Base64.getEncoder().encodeToString(MAP.getBytes(StandardCharsets.UTF_8)));
        SourceMapResolver resolver = new SourceMapResolver(url -> Optional.ofNullable(files.get(url)), url -> url.contains("localhost"));
        Optional<SourceLocation> loc = resolver.resolve(SourceLocation.ofUrl("http://localhost:3000/main.js?v=1", 2, 6));
        assertThat(loc).isPresent();
        assertThat(loc.get().file()).isEqualTo("src/dashboard.tsx");
        assertThat(loc.get().line()).isEqualTo(42);
        assertThat(resolver.resolve(SourceLocation.ofUrl("http://localhost:3000/inline.js", 2, 6))).isPresent();
        assertThat(resolver.resolve(SourceLocation.ofUrl("https://cdn.example.com/x.js", 1, 1))).isEmpty();
    }
}
