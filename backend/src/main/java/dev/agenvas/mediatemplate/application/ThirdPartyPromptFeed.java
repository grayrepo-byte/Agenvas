package dev.agenvas.mediatemplate.application;

/** Bounded text downloads for compiled feed adapters; transport owns URL validation, timeouts and per-request limits. */
@FunctionalInterface
public interface ThirdPartyPromptFeed {
    int MAX_FEED_BYTES = 32 * 1024 * 1024;
    String feed(String url);
}
