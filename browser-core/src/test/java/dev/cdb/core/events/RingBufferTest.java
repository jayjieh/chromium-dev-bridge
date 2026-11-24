package dev.cdb.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RingBufferTest {
    @Test
    void evictsOldestAndPaginates() {
        RingBuffer<String> b = new RingBuffer<>(3);
        for (int i = 1; i <= 5; i++) {
            int n = i;
            b.add(seq -> "v" + n);
        }
        assertThat(b.size()).isEqualTo(3);
        assertThat(b.dropped()).isEqualTo(2);
        RingBuffer.Page<String> p1 = b.query(0, 2, v -> true);
        assertThat(p1.items()).containsExactly("v3", "v4");
        assertThat(p1.hasMore()).isTrue();
        RingBuffer.Page<String> p2 = b.query(p1.lastSeq(), 2, v -> true);
        assertThat(p2.items()).containsExactly("v5");
        assertThat(p2.hasMore()).isFalse();
    }

    @Test
    void replacesInPlace() {
        RingBuffer<String> b = new RingBuffer<>(10);
        b.add(seq -> "a");
        b.add(seq -> "b");
        assertThat(b.replace(1, v -> v + "!")).isTrue();
        assertThat(b.query(0, 10, v -> true).items()).containsExactly("a!", "b");
        assertThat(b.replace(99, v -> v)).isFalse();
    }
}
