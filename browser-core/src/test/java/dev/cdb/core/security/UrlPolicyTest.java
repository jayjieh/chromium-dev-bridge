package dev.cdb.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cdb.core.CoreSettings;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UrlPolicyTest {
    private final UrlPolicy policy = new UrlPolicy(CoreSettings.DEFAULT_DEV_HOSTS, false);

    @Test
    void developmentHostsAreAllowed() {
        assertThat(policy.evaluate("http://localhost:3000/login", Set.of())).isEqualTo(UrlPolicy.Verdict.ALLOWED_DEV);
        assertThat(policy.evaluate("http://127.0.0.1:8080", Set.of())).isEqualTo(UrlPolicy.Verdict.ALLOWED_DEV);
        assertThat(policy.evaluate("http://[::1]:5173/", Set.of())).isEqualTo(UrlPolicy.Verdict.ALLOWED_DEV);
        assertThat(policy.evaluate("http://app.localhost/", Set.of())).isEqualTo(UrlPolicy.Verdict.ALLOWED_DEV);
        assertThat(policy.evaluate("https://shop.test/", Set.of())).isEqualTo(UrlPolicy.Verdict.ALLOWED_DEV);
        assertThat(policy.evaluate("about:blank", Set.of())).isEqualTo(UrlPolicy.Verdict.ALLOWED_DEV);
    }

    @Test
    void otherHostsNeedApproval() {
        assertThat(policy.evaluate("https://example.com", Set.of())).isEqualTo(UrlPolicy.Verdict.DENIED_NEEDS_APPROVAL);
        assertThat(policy.evaluate("https://localhost.evil.com", Set.of())).isEqualTo(UrlPolicy.Verdict.DENIED_NEEDS_APPROVAL);
        assertThat(policy.evaluate("https://example.com/x", Set.of("https://example.com"))).isEqualTo(UrlPolicy.Verdict.ALLOWED_APPROVED);
        assertThat(policy.evaluate("https://example.com:8443/x", Set.of("https://example.com"))).isEqualTo(UrlPolicy.Verdict.DENIED_NEEDS_APPROVAL);
    }

    @Test
    void dangerousSchemesAndTricksAreRejected() {
        assertThat(policy.evaluate("file:///etc/passwd", Set.of())).isEqualTo(UrlPolicy.Verdict.DENIED_SCHEME);
        assertThat(policy.evaluate("javascript:alert(1)", Set.of())).isEqualTo(UrlPolicy.Verdict.DENIED_SCHEME);
        assertThat(policy.evaluate("chrome://settings", Set.of())).isEqualTo(UrlPolicy.Verdict.DENIED_SCHEME);
        assertThat(policy.evaluate("data:text/html,<h1>x</h1>", Set.of())).isEqualTo(UrlPolicy.Verdict.DENIED_SCHEME);
        assertThat(policy.evaluate("http://localhost@evil.com/", Set.of())).isEqualTo(UrlPolicy.Verdict.INVALID);
        assertThat(policy.evaluate("not a url", Set.of())).isEqualTo(UrlPolicy.Verdict.INVALID);
    }

    @Test
    void portSpecificPatterns() {
        UrlPolicy p = new UrlPolicy(List.of("staging.internal:8443"), false);
        assertThat(p.evaluate("https://staging.internal:8443/", Set.of())).isEqualTo(UrlPolicy.Verdict.ALLOWED_DEV);
        assertThat(p.evaluate("https://staging.internal/", Set.of())).isEqualTo(UrlPolicy.Verdict.DENIED_NEEDS_APPROVAL);
    }

    @Test
    void normalizesOrigins() {
        assertThat(UrlPolicy.normalizeOrigin("HTTPS://Example.com:443/path?q")).isEqualTo("https://example.com");
        assertThat(UrlPolicy.normalizeOrigin("http://example.com:8080")).isEqualTo("http://example.com:8080");
    }
}
