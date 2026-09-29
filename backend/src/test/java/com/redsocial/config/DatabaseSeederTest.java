package com.redsocial.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DatabaseSeederTest {
    @Test
    void disabledSeederDoesNotRequireOrModifyDatabase() {
        DatabaseSeeder seeder = new DatabaseSeeder();
        seeder.seedEnabled = false;
        // No Driver is injected. An accidental database call would fail here.
        assertDoesNotThrow(() -> seeder.onStart(null));
    }
}
