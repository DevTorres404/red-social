package com.redsocial.user;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserResourceTest {

    @Test
    void acceptsBoundedConnectionPages() {
        assertDoesNotThrow(() -> UserService.validateConnectionsPage(0));
        assertDoesNotThrow(() -> UserService.validateConnectionsPage(10_000));
    }

    @Test
    void rejectsInvalidConnectionPages() {
        assertThrows(BadRequestException.class, () -> UserService.validateConnectionsPage(-1));
        assertThrows(BadRequestException.class, () -> UserService.validateConnectionsPage(10_001));
    }
}
