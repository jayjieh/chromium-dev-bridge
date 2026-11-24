package dev.cdb.core.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Bounded, thread-safe buffer with monotonically increasing sequence numbers. Oldest entries are evicted.
 */
public class RingBuffer<T> {
    public record Slot<T>(long seq, T value) { }

    private final int capacity;
    private final ArrayDeque<Slot<T>> slots;
    private long nextSeq = 1;
    private long dropped;

    public RingBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        this.slots = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    /** Adds a value; the factory receives the assigned sequence number. */
    public synchronized T add(java.util.function.LongFunction<T> factory) {
        long seq = nextSeq++;
        T value = factory.apply(seq);
        if (slots.size() >= capacity) {
            slots.pollFirst();
            dropped++;
        }
        slots.addLast(new Slot<>(seq, value));
        return value;
    }

    /** Replaces the value stored under seq (if still buffered). */
    public synchronized boolean replace(long seq, UnaryOperator<T> fn) {
        for (Iterator<Slot<T>> it = slots.descendingIterator(); it.hasNext(); ) {
            Slot<T> s = it.next();
            if (s.seq() == seq) {
                List<Slot<T>> copy = new ArrayList<>(slots);
                int idx = copy.indexOf(s);
                copy.set(idx, new Slot<>(seq, fn.apply(s.value())));
                slots.clear();
                slots.addAll(copy);
                return true;
            }
            if (s.seq() < seq) break;
        }
        return false;
    }

    /** Returns up to limit values with seq > afterSeq that match the filter, oldest first. */
    public synchronized Page<T> query(long afterSeq, int limit, Predicate<T> filter) {
        List<T> out = new ArrayList<>();
        long last = afterSeq;
        boolean more = false;
        for (Slot<T> s : slots) {
            if (s.seq() <= afterSeq || !filter.test(s.value())) continue;
            if (out.size() >= limit) {
                more = true;
                break;
            }
            out.add(s.value());
            last = s.seq();
        }
        return new Page<>(out, last, more, dropped);
    }

    public synchronized int size() {
        return slots.size();
    }

    public int capacity() {
        return capacity;
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized void clear() {
        slots.clear();
    }

    public record Page<T>(List<T> items, long lastSeq, boolean hasMore, long dropped) { }
}
