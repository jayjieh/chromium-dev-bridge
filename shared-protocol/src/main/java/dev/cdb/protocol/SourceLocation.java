package dev.cdb.protocol;

public record SourceLocation(String url, String file, Integer line, Integer column) {
    /** Derives the file name (last path segment, without query) from a URL. */
    public static SourceLocation ofUrl(String url, Integer line, Integer column) {
        return new SourceLocation(url, fileName(url), line, column);
    }

    public static String fileName(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
