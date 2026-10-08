package com.redsocial.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    @Test
    void allowsWhileFailuresStayBelowTheLimit() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, 60);
        assertTrue(limiter.allow("alice"));
        limiter.registerFailure("alice");
        limiter.registerFailure("alice");
        assertTrue(limiter.allow("alice")); // 2 < 3
        limiter.registerFailure("alice");
        assertFalse(limiter.allow("alice")); // 3 == 3 → denegado
    }

    @Test
    void deniesOnceTheLimitIsReached() {
        LoginRateLimiter limiter = new LoginRateLimiter(10, 900);
        for (int i = 0; i < 10; i++) {
            limiter.registerFailure("bob");
        }
        assertFalse(limiter.allow("bob"));
    }

    @Test
    void onSuccessResetsTheKey() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 900);
        limiter.registerFailure("carol");
        limiter.registerFailure("carol");
        assertFalse(limiter.allow("carol"));
        limiter.onSuccess("carol");
        assertTrue(limiter.allow("carol")); // login exitoso limpia el contador
    }

    @Test
    void windowExpiryResetsTheCounter() throws InterruptedException {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 1); // ventana de 1 segundo
        limiter.registerFailure("dave");
        limiter.registerFailure("dave");
        assertFalse(limiter.allow("dave"));
        Thread.sleep(1100);
        assertTrue(limiter.allow("dave")); // ventana nueva → vuelve a permitir
    }

    @Test
    void keysAreTrackedIndependently() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 900);
        limiter.registerFailure("eve");
        assertTrue(limiter.allow("eve"));      // 1 fallo < 2
        assertTrue(limiter.allow("mallory"));  // sin fallos
        limiter.registerFailure("eve");
        assertFalse(limiter.allow("eve"));     // 2 fallos == 2 → bloqueado
        assertTrue(limiter.allow("mallory"));  // otra clave no se ve afectada
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new LoginRateLimiter(0, 900));
        assertThrows(IllegalArgumentException.class, () -> new LoginRateLimiter(10, 0));
    }
}