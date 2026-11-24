package dev.cdb.core.security;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials and detectable personal data from headers, URLs, bodies and log text.
 */
public final class Redactor {
    public static final String MASK = "[REDACTED]";

    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i)(authorization|cookie|set-cookie|password|passwd|pwd|secret|token|api[-_]?key|apikey|session|sid|"
                    + "auth|credential|private[-_]?key|access[-_]?key|signature|sig|otp|csrf|xsrf|ssn|cvv|cvc|card[-_]?number)");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)([\"']?(?:password|passwd|secret|token|api[-_]?key|apikey|access[-_]?token|refresh[-_]?token|client[-_]?secret|authorization)[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&}]+)");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern AWS_KEY = Pattern.compile("\\b(AKIA|ASIA)[A-Z0-9]{16}\\b");
    private static final Pattern GENERIC_SK = Pattern.compile("\\b(sk|pk|rk|ghp|gho|xox[abp])[-_][A-Za-z0-9_-]{16,}\\b");

    private Redactor() {
    }

    public static boolean isSensitiveName(String name) {
        return name != null && SENSITIVE_NAME.matcher(name).find();
    }

    public static Map<String, String> headers(Map<String, String> headers) {
        if (headers == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, isSensitiveName(k) ? MASK : text(v, false)));
        return out;
    }

    /** Masks sensitive query parameter values and user-info. */
    public static String url(String url) {
        if (url == null) return null;
        if (url.startsWith("data:")) {
            return url.length() > 64 ? url.substring(0, 64) + "...[truncated]" : url;
        }
        try {
            URI uri = new URI(url);
            String query = uri.getRawQuery();
            String userInfo = uri.getRawUserInfo();
            if (query == null && userInfo == null) return url;
            StringBuilder q = new StringBuilder();
            if (query != null) {
                for (String part : query.split("&")) {
                    if (!q.isEmpty()) q.append('&');
                    int eq = part.indexOf('=');
                    String k = eq >= 0 ? part.substring(0, eq) : part;
                    if (eq >= 0 && isSensitiveName(k)) {
                        q.append(k).append('=').append(MASK);
                    } else {
                        q.append(part);
                    }
                }
            }
            String base = uri.getScheme() + "://" + (userInfo != null ? MASK + "@" : "") + uri.getRawAuthority()
                    .substring(userInfo != null ? userInfo.length() + 1 : 0) + (uri.getRawPath() == null ? "" : uri.getRawPath());
            return base + (query != null ? "?" + q : "") + (uri.getRawFragment() != null ? "#" + uri.getRawFragment() : "");
        } catch (Exception e) {
            return text(url, false);
        }
    }

    /** Redacts tokens and secrets in free text. When personalData is true, e-mails and card numbers too. */
    public static String text(String s, boolean personalData) {
        if (s == null || s.isEmpty()) return s;
        String r = JWT.matcher(s).replaceAll(MASK);
        r = BEARER.matcher(r).replaceAll("$1 " + MASK);
        r = AWS_KEY.matcher(r).replaceAll(MASK);
        r = GENERIC_SK.matcher(r).replaceAll(MASK);
        Matcher kv = KEY_VALUE.matcher(r);
        StringBuilder sb = new StringBuilder();
        while (kv.find()) {
            String v = kv.group(2);
            boolean quoted = v.startsWith("\"") || v.startsWith("'");
            String replacement = v.contains(MASK) ? kv.group() : kv.group(1) + (quoted ? v.charAt(0) + MASK + v.charAt(0) : MASK);
            kv.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        kv.appendTail(sb);
        r = sb.toString();
        if (personalData) {
            r = redactCards(r);
            r = EMAIL.matcher(r).replaceAll("[EMAIL]");
        }
        return r;
    }

    /** Redacts a captured body: JSON keys with sensitive names, tokens, cards, e-mails; truncates to maxBytes. */
    public static String body(String body, String mimeType, int maxChars) {
        if (body == null) return null;
        String b = body.length() > maxChars ? body.substring(0, maxChars) + "...[truncated]" : body;
        String mt = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (mt.contains("x-www-form-urlencoded")) {
            StringBuilder sb = new StringBuilder();
            for (String part : b.split("&")) {
                if (!sb.isEmpty()) sb.append('&');
                int eq = part.indexOf('=');
                String k = eq >= 0 ? part.substring(0, eq) : part;
                sb.append(eq >= 0 && isSensitiveName(k) ? k + "=" + MASK : part);
            }
            b = sb.toString();
        }
        return text(b, true);
    }

    private static String redactCards(String s) {
        Matcher m = CARD.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            m.appendReplacement(sb, Matcher.quoteReplacement(luhn(digits) ? "[CARD]" : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean luhn(String digits) {
        if (digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (n < 0 || n > 9) return false;
            if (alt) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }
}
