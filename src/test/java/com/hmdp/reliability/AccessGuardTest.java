package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** AccessGuard now only handles URI hygiene and rate limits; auth rules live in SecurityConfig. */
class AccessGuardTest {
  @AfterEach
  void clear() {
    UserHolder.removeUser();
  }

  @Test
  void pathParameterAndEncodingTricksAreRejected() throws Exception {
    AccessGuard guard = new AccessGuard(mock(RateLimiter.class));
    String[][] attempts = {
      {"PUT", "/shop;x"},
      {"POST", "/shop;jsessionid=1"},
      {"POST", "/ops;x/outbox/1/retry"},
      {"POST", "/%6fps/seckill/1/publish"},
      {"POST", "//ops/seckill/1/publish"},
      {"POST", "/user/../ops/seckill/1/publish"},
    };
    for (String[] attempt : attempts) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      assertFalse(
          guard.preHandle(
              new MockHttpServletRequest(attempt[0], attempt[1]), response, new Object()),
          attempt[1]);
      assertEquals(400, response.getStatus(), attempt[1]);
    }
  }

  @Test
  void errorDispatchKeepsTheOriginalStatus() throws Exception {
    AccessGuard guard = new AccessGuard(mock(RateLimiter.class));
    MockHttpServletRequest error = new MockHttpServletRequest("GET", "/error;x");
    error.setDispatcherType(DispatcherType.ERROR);
    assertTrue(guard.preHandle(error, new MockHttpServletResponse(), new Object()));
  }

  @Test
  void publicExperienceIsRateLimitedBeforeDataAccess() throws Exception {
    AccessGuard guard = new AccessGuard(mock(RateLimiter.class));
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertFalse(
        guard.preHandle(
            new MockHttpServletRequest("POST", "/experience/visits"), response, new Object()));
    assertEquals(429, response.getStatus());
  }

  @Test
  void loginEndpointsShareThePerIpLimit() throws Exception {
    RateLimiter limits = mock(RateLimiter.class);
    when(limits.allow(anyString(), eq(30), eq(60000L))).thenReturn(false);
    AccessGuard guard = new AccessGuard(limits);
    for (String path : new String[] {"/user/code", "/user/login", "/user/refresh"}) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      assertFalse(
          guard.preHandle(new MockHttpServletRequest("POST", path), response, new Object()));
      assertEquals(429, response.getStatus(), path);
    }
  }

  @Test
  void seckillClaimsAreLimitedPerUser() throws Exception {
    RateLimiter limits = mock(RateLimiter.class);
    when(limits.allow("claim-user:7", 10, 1000)).thenReturn(false);
    AccessGuard guard = new AccessGuard(limits);
    UserDTO user = new UserDTO();
    user.setId(7L);
    UserHolder.saveUser(user);
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertFalse(
        guard.preHandle(
            new MockHttpServletRequest("POST", "/voucher-order/seckill/9"),
            response,
            new Object()));
    assertEquals(429, response.getStatus());
  }
}
