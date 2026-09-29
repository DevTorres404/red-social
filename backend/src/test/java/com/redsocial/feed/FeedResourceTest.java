package com.redsocial.feed;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedResourceTest {

    @Test
    void acceptsBoundedPagination() {
        assertDoesNotThrow(() -> FeedResource.validatePage(0, 1));
        assertDoesNotThrow(() -> FeedResource.validatePage(10_000, 100));
    }

    @Test
    void rejectsInvalidPagination() {
        assertThrows(BadRequestException.class, () -> FeedResource.validatePage(-1, 20));
        assertThrows(BadRequestException.class, () -> FeedResource.validatePage(10_001, 20));
        assertThrows(BadRequestException.class, () -> FeedResource.validatePage(0, 0));
        assertThrows(BadRequestException.class, () -> FeedResource.validatePage(0, 101));
    }
}
