package dev.cdb.driver;

import dev.cdb.core.FakeProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the Playwright provider with the in-memory fake for fast API tests. */
@TestConfiguration
public class FakeProviderConfig {
    @Bean
    @Primary
    FakeProvider fakeProvider() {
        return new FakeProvider();
    }
}
