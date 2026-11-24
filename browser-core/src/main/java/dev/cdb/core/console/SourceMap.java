package dev.cdb.core.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** Minimal Source Map v3 decoder (mappings, sources, sourceRoot). */
public final class SourceMap {
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A mapping segment: generated column -> original source/line/column (all 0-based). */
    record Segment(int genCol, int source, int line, int col) { }

    /** Resolved original position (1-based line/column). */
    public record Original(String source, int line, int column) { }

    private final List<String> sources;
    private final List<List<Segment>> lines;

    private SourceMap(List<String> sources, List<List<Segment>> lines) {
        this.sources = sources;
        this.lines = lines;
    }

    public static SourceMap parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String sourceRoot = root.path("sourceRoot").asText("");
            List<String> sources = new ArrayList<>();
            for (JsonNode s : root.path("sources")) {
                String src = s.asText();
                if (!sourceRoot.isEmpty() && !src.contains("://") && !src.startsWith("/")) {
                    src = sourceRoot.endsWith("/") ? sourceRoot + src : sourceRoot + "/" + src;
                }
                sources.add(src);
            }
            return new SourceMap(sources, decode(root.path("mappings").asText("")));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid source map: " + e.getMessage(), e);
        }
    }

    static List<List<Segment>> decode(String mappings) {
        List<List<Segment>> lines = new ArrayList<>();
        int source = 0, line = 0, col = 0;
        int[] value = new int[1];
        for (String lineStr : mappings.split(";", -1)) {
            List<Segment> segs = new ArrayList<>();
            int genCol = 0;
            if (!lineStr.isEmpty()) {
                for (String seg : lineStr.split(",")) {
                    if (seg.isEmpty()) continue;
                    int[] fields = new int[5];
                    int n = 0;
                    int pos = 0;
                    while (pos < seg.length() && n < 5) {
                        pos = vlq(seg, pos, value);
                        fields[n++] = value[0];
                    }
                    genCol += fields[0];
                    if (n >= 4) {
                        source += fields[1];
                        line += fields[2];
                        col += fields[3];
                        segs.add(new Segment(genCol, source, line, col));
                    }
                }
            }
            lines.add(segs);
        }
        return lines;
    }

    private static int vlq(String s, int pos, int[] out) {
        int result = 0, shift = 0;
        boolean cont;
        do {
            int digit = B64.indexOf(s.charAt(pos++));
            if (digit < 0) throw new IllegalArgumentException("Bad VLQ char");
            cont = (digit & 32) != 0;
            result += (digit & 31) << shift;
            shift += 5;
        } while (cont && pos < s.length());
        boolean negative = (result & 1) == 1;
        result >>= 1;
        out[0] = negative ? -result : result;
        return pos;
    }

    /** Looks up a generated position (1-based line and column). */
    public Original lookup(int line1, int column1) {
        int l = line1 - 1;
        int c = Math.max(0, column1 - 1);
        if (l < 0 || l >= lines.size()) return null;
        Segment best = null;
        for (Segment s : lines.get(l)) {
            if (s.genCol() <= c) best = s;
            else break;
        }
        if (best == null) {
            List<Segment> segs = lines.get(l);
            if (segs.isEmpty()) return null;
            best = segs.get(0);
        }
        if (best.source() < 0 || best.source() >= sources.size()) return null;
        return new Original(sources.get(best.source()), best.line() + 1, best.col() + 1);
    }

    /** Normalises bundler source URLs (webpack://app/./src/x.ts -> src/x.ts). */
    public static String normalizeSourcePath(String source) {
        if (source == null) return null;
        String s = source;
        int scheme = s.indexOf("://");
        if (scheme >= 0 && s.startsWith("webpack")) {
            s = s.substring(scheme + 3);
            int slash = s.indexOf('/');
            s = slash >= 0 ? s.substring(slash + 1) : s;
        } else if (scheme >= 0) {
            try {
                s = java.net.URI.create(s).getPath();
            } catch (IllegalArgumentException ignored) {
                // keep as is
            }
        }
        while (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("../")) s = s.substring(3);
        if (s.startsWith("/") && s.length() > 1) s = s.substring(1);
        return s;
    }
}
