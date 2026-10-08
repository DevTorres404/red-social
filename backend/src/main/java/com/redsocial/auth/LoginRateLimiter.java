package com.redsocial.auth;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-window login rate limiter.
 *
 * Plain POJO: constructed with {@code (maxAttempts, windowSeconds)}, with no
 * Quarkus/CDI dependency, so it is fully unit-testable. Each client key tracks
 * how many failed attempts happened since the current window started; once
 * {@code windowSeconds} elapse the counter resets. Windows are (re)created
 * lazily on access and entries are removed on success, so no background thread
 * is needed.
 *
 * Thread-safe: every mutation goes through {@link ConcurrentHashMap#compute},
 * which is atomic per key.
 */
public final class LoginRateLimiter {

    private final int maxAttempts;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(int failures, long windowStartMillis) {}

    public LoginRateLimiter(int maxAttempts, long windowSeconds) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.windowMillis = TimeUnit.SECONDS.toMillis(windowSeconds);
    }

    private boolean expired(Window window, long now) {
        return now - window.windowStartMillis() >= windowMillis;
    }

    /**
     * @return true while the failures recorded in the current window are below
     *         {@code maxAttempts}; false once the limit has been reached.
     */
    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Window current = windows.compute(key, (k, existing) ->
                existing == null || expired(existing, now) ? new Window(0, now) : existing);
        return current.failures() < maxAttempts;
    }

    /** Records one failed attempt for the key within the current window. */
    public void registerFailure(String key) {
        long now = System.currentTimeMillis();
        windows.compute(key, (k, existing) ->
                existing == null || expired(existing, now)
                        ? new Window(1, now)
                        : new Window(existing.failures() + 1, existing.windowStartMillis()));
    }

    /** Clears the limiter state for the key (successful login). */
    public void onSuccess(String key) {
        windows.remove(key);
    }
}