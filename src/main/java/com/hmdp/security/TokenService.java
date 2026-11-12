package com.hmdp.security;

import com.hmdp.dto.TokenPairDTO;
import com.hmdp.dto.UserDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues, rotates and revokes login tokens.
 *
 * <ul>
 *   <li>Access token: HS256 JWT, short-lived, carries the public profile so requests need no
 *       lookup.
 *   <li>Refresh token: random opaque value; only its SHA-256 is stored in Redis. Each use deletes
 *       it and issues a new pair, so a stolen refresh token works at most once.
 *   <li>Logout: deletes the refresh token and deny-lists the access token's {@code jti} until it
 *       would have expired anyway.
 * </ul>
 */
@Service
public class TokenService {
  static final String ISSUER = "hmdp";
  private static final String REFRESH_KEY = "login:refresh:";
  private static final String REVOKED_KEY = "login:revoked:";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JwtEncoder encoder;
  private final StringRedisTemplate redis;
  private final Duration accessTtl;
  private final Duration refreshTtl;

  public TokenService(
      JwtEncoder encoder,
      StringRedisTemplate redis,
      @Value("${hmdp.security.access-token-ttl:15m}") Duration accessTtl,
      @Value("${hmdp.security.refresh-token-ttl:7d}") Duration refreshTtl) {
    this.encoder = encoder;
    this.redis = redis;
    this.accessTtl = accessTtl;
    this.refreshTtl = refreshTtl;
  }

  public TokenPairDTO issue(UserDTO user) {
    String refreshToken = newRefreshToken();
    String key = REFRESH_KEY + sha256(refreshToken);
    redis.opsForHash().putAll(key, user.toRedisHash());
    redis.expire(key, refreshTtl);
    return new TokenPairDTO(accessToken(user), refreshToken, accessTtl.toSeconds());
  }

  /** Rotates a refresh token. Returns null if it is unknown, expired or already used. */
  public TokenPairDTO refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 128) {
      return null;
    }
    String key = REFRESH_KEY + sha256(refreshToken);
    Map<Object, Object> stored = redis.opsForHash().entries(key);
    // delete() returns true for exactly one caller, which makes concurrent reuse fail closed.
    if (stored.isEmpty() || !Boolean.TRUE.equals(redis.delete(key))) {
      return null;
    }
    return issue(UserDTO.fromRedisHash(stored));
  }

  public void revoke(Jwt accessToken, String refreshToken) {
    if (refreshToken != null && !refreshToken.isBlank() && refreshToken.length() <= 128) {
      redis.delete(REFRESH_KEY + sha256(refreshToken));
    }
    if (accessToken != null && accessToken.getId() != null && accessToken.getExpiresAt() != null) {
      long remaining = Duration.between(Instant.now(), accessToken.getExpiresAt()).toMillis();
      if (remaining > 0) {
        redis
            .opsForValue()
            .set(REVOKED_KEY + accessToken.getId(), "1", remaining, TimeUnit.MILLISECONDS);
      }
    }
  }

  public boolean isRevoked(String jti) {
    return jti != null && Boolean.TRUE.equals(redis.hasKey(REVOKED_KEY + jti));
  }

  private String accessToken(UserDTO user) {
    Instant now = Instant.now();
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(String.valueOf(user.getId()))
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(now.plus(accessTtl));
    if (user.getNickName() != null) {
      claims.claim("nickName", user.getNickName());
    }
    if (user.getIcon() != null) {
      claims.claim("icon", user.getIcon());
    }
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
  }

  private static String newRefreshToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
