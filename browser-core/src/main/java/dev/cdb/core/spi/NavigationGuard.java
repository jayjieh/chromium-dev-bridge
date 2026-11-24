package dev.cdb.core.spi;

/** Decides whether a top-level navigation may proceed. Called on the provider's browser thread; must be fast. */
@FunctionalInterface
public interface NavigationGuard {
    boolean isAllowed(String url);

    NavigationGuard ALLOW_ALL = url -> true;
}
