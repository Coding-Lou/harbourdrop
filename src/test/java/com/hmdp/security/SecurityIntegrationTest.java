package com.hmdp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** End-to-end checks of the email login, token lifecycle and route authorization. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "citylens.reliability.scheduling-enabled=false")
class SecurityIntegrationTest extends IntegrationTestBase {
  @Autowired TestRestTemplate http;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper();

  private JsonNode post(String path, Object body, HttpHeaders headers) throws Exception {
    ResponseEntity<String> response =
        http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    return json.readTree(response.getBody());
  }

  private int status(HttpMethod method, String path, HttpHeaders headers) {
    return http.exchange(path, method, new HttpEntity<>(headers), String.class)
        .getStatusCode()
        .value();
  }

  private static HttpHeaders bearerHeader(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  @Test
  void emailCodeLoginRefreshRotationAndLogout() throws Exception {
    String email = "Security.Test." + System.nanoTime() + "@Example.com";
    String normalized = email.toLowerCase();
    assertTrue(
        post("/user/code?email=" + email, null, new HttpHeaders()).path("success").asBoolean());
    String code = redis.opsForValue().get("login:code:" + normalized);
    assertNotNull(code);

    JsonNode wrong =
        post("/user/login", Map.of("email", email, "code", "000000"), new HttpHeaders());
    if (!"000000".equals(code)) {
      assertFalse(wrong.path("success").asBoolean());
    }
    JsonNode login = post("/user/login", Map.of("email", email, "code", code), new HttpHeaders());
    assertTrue(login.path("success").asBoolean(), login.toString());
    String access = login.path("data").path("accessToken").asText();
    String refresh = login.path("data").path("refreshToken").asText();
    assertEquals(3, access.split("\\.").length, "access token is a JWT");
    // First login registers the user under the normalized address.
    assertEquals(
        1L,
        jdbc.queryForObject("SELECT COUNT(*) FROM tb_user WHERE email=?", Long.class, normalized));
    // The code is single-use.
    assertFalse(
        post("/user/login", Map.of("email", email, "code", code), new HttpHeaders())
            .path("success")
            .asBoolean());

    assertEquals(200, status(HttpMethod.GET, "/user/me", bearerHeader(access)));

    JsonNode rotated = post("/user/refresh", Map.of("refreshToken", refresh), new HttpHeaders());
    assertTrue(rotated.path("success").asBoolean());
    String newRefresh = rotated.path("data").path("refreshToken").asText();
    String newAccess = rotated.path("data").path("accessToken").asText();
    assertNotEquals(refresh, newRefresh);
    // A refresh token works exactly once.
    assertFalse(
        post("/user/refresh", Map.of("refreshToken", refresh), new HttpHeaders())
            .path("success")
            .asBoolean());

    assertTrue(
        post("/user/logout", Map.of("refreshToken", newRefresh), bearerHeader(newAccess))
            .path("success")
            .asBoolean());
    assertEquals(401, status(HttpMethod.GET, "/user/me", bearerHeader(newAccess)));
    assertFalse(
        post("/user/refresh", Map.of("refreshToken", newRefresh), new HttpHeaders())
            .path("success")
            .asBoolean());
  }

  @Test
  void anonymousForgedAndRegularUsersGetTheRightStatus() {
    assertEquals(200, status(HttpMethod.GET, "/shop/1", new HttpHeaders()));
    assertEquals(401, status(HttpMethod.GET, "/user/me", new HttpHeaders()));
    assertEquals(401, status(HttpMethod.GET, "/user/me", bearerHeader("not-a-jwt")));
    String forgedAlg =
        "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxMDEwIiwiaXNzIjoiaG1kcCJ9."; // alg=none, admin subject
    assertEquals(401, status(HttpMethod.GET, "/ops/seckill/tasks", bearerHeader(forgedAlg)));
    assertEquals(403, status(HttpMethod.GET, "/ops/seckill/tasks", bearer(1012)));
    assertEquals(200, status(HttpMethod.GET, "/ops/seckill/tasks", bearer(1010)));
    assertEquals(403, status(HttpMethod.PUT, "/shop", bearer(1012)));
  }

  @Test
  void monitorCredentialOnlyReadsMetrics() {
    HttpHeaders monitor = bearerHeader("hmdp_dev_metrics");
    assertEquals(200, status(HttpMethod.GET, "/actuator/prometheus", monitor));
    assertEquals(401, status(HttpMethod.GET, "/ops/seckill/tasks", monitor));
    assertEquals(401, status(HttpMethod.GET, "/user/me", monitor));
  }

  @Test
  void firewallRejectsPathTricksBeforeAnyRule() {
    assertEquals(400, status(HttpMethod.PUT, "/shop;x", bearer(1012)));
    assertEquals(400, status(HttpMethod.GET, "/ops;x/seckill/tasks", bearer(1012)));
  }
}
