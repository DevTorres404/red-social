package com.redsocial.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Wires the plain {@link LoginRateLimiter} POJO into CDI using the configured
 * limits. Kept separate so the core stays a plain, unit-testable POJO.
 */
@ApplicationScoped
public class LoginRateLimiterProducer {

    @Produces
    @ApplicationScoped
    LoginRateLimiter loginRateLimiter(
            @ConfigProperty(name = "app.auth.login.max-attempts", defaultValue = "10") int maxAttempts,
            @ConfigProperty(name = "app.auth.login.window-seconds", defaultValue = "900") long windowSeconds) {
        return new LoginRateLimiter(maxAttempts, windowSeconds);
    }
}