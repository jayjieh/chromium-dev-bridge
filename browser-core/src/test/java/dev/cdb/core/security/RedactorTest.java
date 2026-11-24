package dev.cdb.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactorTest {
    @Test
    void redactsSensitiveHeaders() {
        Map<String, String> h = Redactor.headers(Map.of("Authorization", "Bearer abc", "Cookie", "sid=1", "X-API-Key", "k",
                "Content-Type", "application/json"));
        assertThat(h.get("Authorization")).isEqualTo(Redactor.MASK);
        assertThat(h.get("Cookie")).isEqualTo(Redactor.MASK);
        assertThat(h.get("X-API-Key")).isEqualTo(Redactor.MASK);
        assertThat(h.get("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void redactsUrlQueryAndUserInfo() {
        assertThat(Redactor.url("http://localhost/cb?code=1&access_token=abc&page=2"))
                .isEqualTo("http://localhost/cb?code=1&access_token=[REDACTED]&page=2");
        assertThat(Redactor.url("https://user:pw@host.test/x")).isEqualTo("https://[REDACTED]@host.test/x");
        assertThat(Redactor.url("http://localhost:3000/plain")).isEqualTo("http://localhost:3000/plain");
    }

    @Test
    void redactsTokensInText() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlMTIz";
        assertThat(Redactor.text("token " + jwt, false)).doesNotContain(jwt);
        assertThat(Redactor.text("Authorization: Bearer abcdefghij123", false)).doesNotContain("abcdefghij123");
        assertThat(Redactor.text("{\"password\": \"hunter2\", \"user\": \"bob\"}", false)).doesNotContain("hunter2").contains("bob");
        assertThat(Redactor.text("api_key=sk_live_1234567890abcdefgh", false)).doesNotContain("1234567890abcdefgh");
        assertThat(Redactor.text("AKIAABCDEFGHIJKLMNOP", false)).isEqualTo(Redactor.MASK);
    }

    @Test
    void redactsPersonalDataInBodies() {
        String body = Redactor.body("{\"email\":\"jane@example.org\",\"card\":\"4111 1111 1111 1111\",\"id\":\"1234567890123\"}",
                "application/json", 10_000);
        assertThat(body).contains("[EMAIL]").contains("[CARD]").contains("1234567890123");
        assertThat(Redactor.body("user=a&password=b", "application/x-www-form-urlencoded", 1000)).isEqualTo("user=a&password=[REDACTED]");
        assertThat(Redactor.body("x".repeat(50), "text/plain", 10)).endsWith("[truncated]");
    }

    @Test
    void luhn() {
        assertThat(Redactor.luhn("4111111111111111")).isTrue();
        assertThat(Redactor.luhn("4111111111111112")).isFalse();
    }
}
