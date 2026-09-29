package com.redsocial.auth.dto;

import com.redsocial.user.User;

/**
 * Response returned on successful register or login.
 *
 * token     — signed JWT the client must send as: Authorization: Bearer <token>
 * expiresIn — seconds until the token expires (frontend uses this to schedule refresh)
 * user      — public profile only (no passwordHash, never leaks)
 */
public record AuthResponse(
        String token,
        long expiresIn,
        User.UserProfile user
) {}
