package dev.cdb.core;

import java.time.Duration;

/** Metrics hook; implemented with Micrometer by the driver. Must never receive sensitive data. */
public interface DriverMetrics {
    default void sessionStarted(Duration startup) { }
    default void sessionClosed() { }
    default void sessionFailed() { }
    default void action(String type, Duration duration, boolean success) { }
    default void navigationFailed() { }
    default void browserCrashed() { }
    default void bufferUtilization(String buffer, double ratio) { }

    DriverMetrics NOOP = new DriverMetrics() { };
}
