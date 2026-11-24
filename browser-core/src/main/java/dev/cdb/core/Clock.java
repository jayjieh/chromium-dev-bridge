package dev.cdb.core;

import java.time.Instant;

/** Time source; replaceable in tests. */
@FunctionalInterface
public interface Clock {
    Instant now();

    Clock SYSTEM = Instant::now;
}
