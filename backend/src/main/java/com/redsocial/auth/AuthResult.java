package com.redsocial.auth;

import com.redsocial.auth.dto.AuthResponse;
import com.redsocial.user.User;

/**
 * Internal result of authentication operations that includes the raw refresh token
 * for cookie setting. Not exposed outside the auth package.
 */
record AuthResult(AuthResponse response, String rawRefreshToken) {}