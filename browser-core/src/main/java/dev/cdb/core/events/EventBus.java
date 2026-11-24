package dev.cdb.core.events;

import dev.cdb.protocol.DriverEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** In-process publish/subscribe for driver events. Subscribers must not block (enqueue and return). */
public class EventBus {
    private static final Logger log = LoggerFactory.getLogger(EventBus.class);
    private final List<Consumer<DriverEvent>> subscribers = new CopyOnWriteArrayList<>();

    public AutoCloseable subscribe(Consumer<DriverEvent> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    public void publish(DriverEvent event) {
        for (Consumer<DriverEvent> s : subscribers) {
            try {
                s.accept(event);
            } catch (RuntimeException e) {
                log.warn("Event subscriber failed for {}: {}", event.type(), e.toString());
            }
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
