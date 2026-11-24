package dev.cdb.driver.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cdb.core.Principal;
import dev.cdb.driver.CdbProperties;
import dev.cdb.driver.metrics.MicrometerDriverMetrics;
import dev.cdb.protocol.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First line of defence for every HTTP request (including WebSocket upgrades):
 * correlation id, Host-header allowlist (DNS rebinding), Origin rejection (web pages), body size limit,
 * bearer-token authentication and rate limiting.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = "cdb.principal";
    private static final Pattern SESSION_PATH = Pattern.compile("/api/v1/sessions/([A-Za-z0-9-]+)");
    private static final Pattern TOOL = Pattern.compile("[a-z_]{1,64}");

    private final TokenService tokens;
    private final ObjectMapper mapper;
    private final CdbProperties.Security config;
    private final RateLimiter limiter;
    private final MicrometerDriverMetrics metrics;

    public SecurityFilter(TokenService tokens, ObjectMapper mapper, CdbProperties props, MicrometerDriverMetrics metrics) {
        this.tokens = tokens;
        this.mapper = mapper;
        this.config = props.security();
        this.limiter = new RateLimiter(config.rateLimitPerSecond(), config.rateLimitBurst());
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String corr = req.getHeader("X-Correlation-Id");
        if (corr == null || !corr.matches("[A-Za-z0-9._-]{1,64}")) corr = UUID.randomUUID().toString();
        MDC.put("correlationId", corr);
        res.setHeader("X-Correlation-Id", corr);
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Cache-Control", "no-store");
        Matcher m = SESSION_PATH.matcher(req.getRequestURI());
        if (m.find()) MDC.put("sessionId", m.group(1));
        long t0 = System.nanoTime();
        try {
            if (!hostAllowed(req.getHeader("Host"))) {
                problem(res, ErrorCode.ACCESS_DENIED, "Host header not allowed (DNS rebinding protection)", corr);
                return;
            }
            String origin = req.getHeader("Origin");
            if (origin != null && !config.allowedOrigins().contains(origin)) {
                problem(res, ErrorCode.ACCESS_DENIED, "Cross-origin requests are not allowed", corr);
                return;
            }
            long len = req.getContentLengthLong();
            if (len > config.maxRequestBytes()) {
                problem(res, ErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds " + config.maxRequestBytes() + " bytes", corr);
                return;
            }
            String path = req.getRequestURI();
            boolean open = path.equals("/api/v1/health") || path.equals("/actuator/health");
            Principal principal = null;
            if (!open) {
                String auth = req.getHeader("Authorization");
                String bearer = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : null;
                principal = tokens.authenticate(bearer, req.getHeader("X-CDB-Client")).orElse(null);
                if (principal == null) {
                    res.setHeader("WWW-Authenticate", "Bearer");
                    problem(res, ErrorCode.UNAUTHENTICATED, "Missing or invalid bearer token", corr);
                    return;
                }
                if (!limiter.tryAcquire(principal.name())) {
                    res.setHeader("Retry-After", "1");
                    problem(res, ErrorCode.RATE_LIMITED, "Too many requests", corr);
                    return;
                }
                req.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            }
            chain.doFilter(len < 0 ? new LimitedRequest(req, config.maxRequestBytes()) : req, res);
        } finally {
            String tool = req.getHeader("X-CDB-Tool");
            if (tool != null && TOOL.matcher(tool).matches()) {
                metrics.mcpTool(tool, Duration.ofNanos(System.nanoTime() - t0), res.getStatus());
            }
            MDC.remove("correlationId");
            MDC.remove("sessionId");
        }
    }

    public boolean hostAllowed(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end > 0 ? h.substring(0, end + 1) : h;
        } else {
            int colon = h.indexOf(':');
            if (colon >= 0) h = h.substring(0, colon);
        }
        List<String> allowed = config.allowedHostHeaders();
        return allowed.contains(h);
    }

    private void problem(HttpServletResponse res, ErrorCode code, String detail, String corr) throws IOException {
        res.setStatus(code.httpStatus());
        res.setContentType("application/problem+json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://cdb.dev/problems/" + code.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        body.put("title", code.name());
        body.put("status", code.httpStatus());
        body.put("detail", detail);
        body.put("code", code.name());
        body.put("correlationId", corr);
        mapper.writeValue(res.getOutputStream(), body);
    }

    /** Enforces the body limit for chunked requests without Content-Length. */
    static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long max;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                long count;

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0 && ++count > max) throw new IOException("Request body too large");
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = in.read(b, off, len);
                    if (n > 0 && (count += n) > max) throw new IOException("Request body too large");
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener l) {
                    in.setReadListener(l);
                }
            };
        }

        @SuppressWarnings("unused")
        InputStream raw() throws IOException {
            return super.getInputStream();
        }
    }
}
