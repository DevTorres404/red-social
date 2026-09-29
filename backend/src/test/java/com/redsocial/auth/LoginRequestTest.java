package com.redsocial.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsocial.auth.dto.LoginRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsUsernameThroughIdentifier() throws Exception {
        var request = mapper.readValue("""
                {"identifier":"alice","password":"pass1234"}
                """, LoginRequest.class);
        assertEquals("alice", request.identifier());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void keepsLegacyEmailRequestCompatible() throws Exception {
        var request = mapper.readValue("""
                {"email":"alice@example.test","password":"pass1234"}
                """, LoginRequest.class);
        assertEquals("alice@example.test", request.identifier());
    }
}
