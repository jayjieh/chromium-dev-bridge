package dev.cdb.protocol;

import java.util.List;

/** A page of buffered events. Continue with afterSeq = lastSeq. */
public record EventPage<T>(List<T> items, long lastSeq, boolean hasMore, long dropped) {
}
