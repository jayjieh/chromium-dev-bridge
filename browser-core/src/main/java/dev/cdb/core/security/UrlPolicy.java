package dev.cdb.core.security;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which URLs a session may load. Development hosts are allowed by default; any other origin needs an
 * explicit ORIGIN approval for the session.
 */
public class UrlPolicy {
    public enum Verdict { ALLOWED_DEV, ALLOWED_APPROVED, DENIED_NEEDS_APPROVAL, DENIED_SCHEME, INVALID }

    private static final java.util.regex.Pattern SCHEME = java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private final List<String> devHostPatterns;
    private final boolean allowFileUrls;

    public UrlPolicy(List<String> devHostPatterns, boolean allowFileUrls) {
        this.devHostPatterns = devHostPatterns.stream().map(s -> s.toLowerCase(Locale.ROOT).trim()).toList();
        this.allowFileUrls = allowFileUrls;
    }

    public Verdict evaluate(String url, Set<String> approvedOrigins) {
        if (url == null || url.isBlank()) return Verdict.INVALID;
        String trimmed = url.trim();
        if (trimmed.equals("about:blank")) return Verdict.ALLOWED_DEV;
        java.util.regex.Matcher schemeMatch = SCHEME.matcher(trimmed);
        if (schemeMatch.find()) {
            String sch = schemeMatch.group(1).toLowerCase(Locale.ROOT);
            if (!sch.equals("http") && !sch.equals("https") && !sch.equals("ws") && !sch.equals("wss") && !sch.equals("file")) {
                return Verdict.DENIED_SCHEME;
            }
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (Exception e) {
            return Verdict.INVALID;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("file")) {
            return allowFileUrls ? Verdict.ALLOWED_DEV : Verdict.DENIED_SCHEME;
        }
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("ws") && !scheme.equals("wss")) {
            return Verdict.DENIED_SCHEME;
        }
        if (uri.getRawUserInfo() != null) {
            return Verdict.INVALID; // http://user@evil.com tricks
        }
        String host = uri.getHost();
        if (host == null) return Verdict.INVALID;
        host = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (isDevHost(host, port)) return Verdict.ALLOWED_DEV;
        String origin = origin(uri);
        if (approvedOrigins != null && approvedOrigins.contains(origin)) return Verdict.ALLOWED_APPROVED;
        return Verdict.DENIED_NEEDS_APPROVAL;
    }

    public boolean isAllowed(String url, Set<String> approvedOrigins) {
        Verdict v = evaluate(url, approvedOrigins);
        return v == Verdict.ALLOWED_DEV || v == Verdict.ALLOWED_APPROVED;
    }

    public boolean isDevelopmentUrl(String url) {
        return evaluate(url, Set.of()) == Verdict.ALLOWED_DEV;
    }

    boolean isDevHost(String host, int port) {
        String bracketed = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        for (String p : devHostPatterns) {
            String patternHost = p;
            Integer patternPort = null;
            int colon = p.lastIndexOf(':');
            if (colon > 0 && !p.endsWith("]") && p.indexOf(']') < colon) {
                patternHost = p.substring(0, colon);
                try {
                    patternPort = Integer.parseInt(p.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    patternHost = p;
                }
            }
            if (patternPort != null && patternPort != port) continue;
            if (patternHost.startsWith("*.")) {
                String suffix = patternHost.substring(1);
                if (host.endsWith(suffix) && host.length() > suffix.length()) return true;
            } else if (patternHost.equals(host) || patternHost.equals(bracketed)) {
                return true;
            }
        }
        return false;
    }

    /** Normalised origin "scheme://host[:port]" with default ports removed. */
    public static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    /** Normalises an origin string supplied in an approval request. */
    public static String normalizeOrigin(String value) {
        try {
            URI uri = new URI(value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("Origin must look like https://host[:port]");
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("Origin scheme must be http or https");
            }
            return origin(uri);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid origin: " + value);
        }
    }

    public List<String> devHostPatterns() {
        return devHostPatterns;
    }
}
