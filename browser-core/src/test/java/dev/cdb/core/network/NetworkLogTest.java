package dev.cdb.core.network;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cdb.protocol.NetworkEntry;
import dev.cdb.protocol.NetworkQuery;
import dev.cdb.protocol.NetworkState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NetworkLogTest {
    static NetworkEntry entry(String id, String url, String type, NetworkState state, Integer status, Double ms) {
        return new NetworkEntry(0, id, "s", "tab-1", "GET", url, type, state, status, null, null, Instant.now(), ms, null, null,
                false, false, null, null, null, null);
    }

    @Test
    void upsertMovesEntryToEndAndFilters() {
        NetworkLog log = new NetworkLog(10);
        log.upsert(entry("1", "http://localhost/api/users", "fetch", NetworkState.PENDING, null, null));
        log.upsert(entry("2", "http://localhost/app.css", "stylesheet", NetworkState.COMPLETED, 200, 5.0));
        log.upsert(entry("1", "http://localhost/api/users", "fetch", NetworkState.COMPLETED, 500, 120.0));
        log.upsert(entry("3", "http://localhost/api/missing", "xhr", NetworkState.COMPLETED, 404, 3.0));
        assertThat(log.query(NetworkQuery.all()).items()).extracting(NetworkEntry::requestId).containsExactly("2", "1", "3");
        assertThat(log.query(NetworkQuery.failed()).items()).extracting(NetworkEntry::requestId).containsExactly("1", "3");
        assertThat(log.query(new NetworkQuery(null, true, null, null, null, null, null, null, null)).items()).hasSize(2);
        assertThat(log.query(new NetworkQuery(null, null, null, "4xx", null, null, null, null, null)).items())
                .extracting(NetworkEntry::requestId).containsExactly("3");
        assertThat(log.query(new NetworkQuery(null, null, null, null, null, "**/api/*", null, null, null)).items()).hasSize(2);
        assertThat(log.query(new NetworkQuery(null, null, null, null, null, null, 100, null, null)).items())
                .extracting(NetworkEntry::requestId).containsExactly("1");
    }

    @Test
    void boundedCapacity() {
        NetworkLog log = new NetworkLog(2);
        for (int i = 0; i < 5; i++) log.upsert(entry("r" + i, "http://localhost/" + i, "fetch", NetworkState.PENDING, null, null));
        assertThat(log.size()).isEqualTo(2);
        assertThat(log.query(NetworkQuery.all()).dropped()).isEqualTo(3);
    }
}
