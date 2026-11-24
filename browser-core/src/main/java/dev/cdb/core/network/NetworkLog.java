package dev.cdb.core.network;

import dev.cdb.protocol.NetworkEntry;
import dev.cdb.protocol.NetworkQuery;
import dev.cdb.protocol.EventPage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bounded log of network entries keyed by request id. Updating an entry gives it a new sequence number,
 * so clients polling with afterSeq see state changes (PENDING -> COMPLETED).
 */
public class NetworkLog {
    private final int capacity;
    private final LinkedHashMap<String, NetworkEntry> entries = new LinkedHashMap<>();
    private long nextSeq = 1;
    private long dropped;

    public NetworkLog(int capacity) {
        this.capacity = capacity;
    }

    public synchronized NetworkEntry upsert(NetworkEntry entry) {
        entries.remove(entry.requestId());
        NetworkEntry stored = entry.withSeq(nextSeq++);
        entries.put(entry.requestId(), stored);
        while (entries.size() > capacity) {
            Iterator<String> it = entries.keySet().iterator();
            it.next();
            it.remove();
            dropped++;
        }
        return stored;
    }

    public synchronized NetworkEntry get(String requestId) {
        return entries.get(requestId);
    }

    public synchronized NetworkEntry findLatestByUrl(String url) {
        NetworkEntry found = null;
        for (NetworkEntry e : entries.values()) {
            if (e.url().equals(url)) found = e;
        }
        return found;
    }

    public synchronized EventPage<NetworkEntry> query(NetworkQuery q) {
        long after = q.afterSeq() == null ? 0 : q.afterSeq();
        int limit = q.limit() == null ? 200 : Math.max(1, Math.min(1000, q.limit()));
        List<NetworkEntry> sorted = new ArrayList<>(entries.values());
        List<NetworkEntry> out = new ArrayList<>();
        long last = after;
        boolean more = false;
        Pattern glob = q.urlPattern() != null && q.urlPattern().contains("*") ? globToRegex(q.urlPattern()) : null;
        for (NetworkEntry e : sorted) {
            if (e.seq() <= after || !matches(e, q, glob)) continue;
            if (out.size() >= limit) {
                more = true;
                break;
            }
            out.add(e);
            last = e.seq();
        }
        return new EventPage<>(out, last, more, dropped);
    }

    public synchronized int size() {
        return entries.size();
    }

    public int capacity() {
        return capacity;
    }

    public synchronized List<NetworkEntry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    static boolean matches(NetworkEntry e, NetworkQuery q, Pattern glob) {
        if (q.tabId() != null && !q.tabId().equals(e.tabId())) return false;
        if (Boolean.TRUE.equals(q.fetchXhrOnly())) {
            String rt = e.resourceType() == null ? "" : e.resourceType().toLowerCase(Locale.ROOT);
            if (!rt.equals("fetch") && !rt.equals("xhr")) return false;
        }
        if (Boolean.TRUE.equals(q.failedOnly()) && !e.isFailure()) return false;
        if (q.resourceType() != null && !q.resourceType().equalsIgnoreCase(e.resourceType())) return false;
        if (q.status() != null && !q.status().isBlank()) {
            String s = q.status().trim().toLowerCase(Locale.ROOT);
            if (e.status() == null) return false;
            if (s.length() == 3 && s.endsWith("xx")) {
                if (e.status() / 100 != s.charAt(0) - '0') return false;
            } else {
                try {
                    if (e.status() != Integer.parseInt(s)) return false;
                } catch (NumberFormatException ex) {
                    return false;
                }
            }
        }
        if (q.urlPattern() != null && !q.urlPattern().isBlank()) {
            if (glob != null ? !glob.matcher(e.url()).matches() : !e.url().contains(q.urlPattern())) return false;
        }
        if (q.minDurationMs() != null && (e.durationMs() == null || e.durationMs() < q.minDurationMs())) return false;
        return true;
    }

    public static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                } else {
                    sb.append(".*");
                }
            } else if (c == '?') {
                sb.append('.');
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return Pattern.compile(sb.toString());
    }

    /** Minimal summary map used in reports. */
    public static Map<String, Object> summary(NetworkEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", e.method());
        m.put("url", e.url());
        m.put("status", e.status());
        m.put("failure", e.failureText());
        return m;
    }
}
