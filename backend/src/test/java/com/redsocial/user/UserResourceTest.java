package com.redsocial.user;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserResourceTest {

    @Test
    void acceptsBoundedConnectionPages() {
        assertDoesNotThrow(() -> UserResource.validateConnectionsPage(0));
        assertDoesNotThrow(() -> UserResource.validateConnectionsPage(10_000));
    }

    @Test
    void rejectsInvalidConnectionPages() {
        assertThrows(BadRequestException.class, () -> UserResource.validateConnectionsPage(-1));
        assertThrows(BadRequestException.class, () -> UserResource.validateConnectionsPage(10_001));
    }
}
