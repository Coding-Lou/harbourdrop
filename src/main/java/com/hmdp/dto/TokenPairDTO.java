package com.hmdp.dto;

/**
 * Tokens returned after login or refresh.
 *
 * @param accessToken short-lived JWT for the {@code Authorization: Bearer} header
 * @param refreshToken opaque, single-use token for {@code POST /user/refresh}
 * @param expiresIn access-token lifetime in seconds
 */
public record TokenPairDTO(String accessToken, String refreshToken, long expiresIn) {}
