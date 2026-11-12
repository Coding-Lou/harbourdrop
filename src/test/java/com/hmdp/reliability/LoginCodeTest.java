package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class LoginCodeTest {
  @Test
  void sendAndLoginAreLimitedBeforeGeneratingOrConsumingCode() {
    UserServiceImpl service = new UserServiceImpl();
    RateLimiter limiter = mock(RateLimiter.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ReflectionTestUtils.setField(service, "rateLimiter", limiter);
    ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
    assertFalse(service.sendCode("someone@example.com").getSuccess());
    LoginFormDTO form = new LoginFormDTO();
    form.setEmail("someone@example.com");
    form.setCode("123456");
    assertFalse(service.login(form).getSuccess());
    verifyNoInteractions(redis);
  }
}
