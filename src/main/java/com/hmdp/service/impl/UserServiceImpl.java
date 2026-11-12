package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.TokenPairDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.reliability.RateLimiter;
import com.hmdp.reliability.RedisScripts;
import com.hmdp.repository.UserRepository;
import com.hmdp.security.LoginCodeSender;
import com.hmdp.security.TokenService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RandomCodes;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserServiceImpl implements IUserService {
  @Resource
  private UserRepository userRepository;

  @Override
  public User getById(Long id) {
    return id == null ? null : userRepository.findById(id).orElse(null);
  }

  @Override
  public List<User> listByIds(Collection<Long> ids) {
    return userRepository.findAllById(ids);
  }

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Resource
  private RateLimiter rateLimiter;

  private static final DefaultRedisScript<Long> CONSUME_CODE =
      RedisScripts.number("consume-code.lua");

  @Resource
  private TokenService tokenService;

  @Resource
  private LoginCodeSender loginCodeSender;

  private static String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  @Override
  public Result sendCode(String rawEmail) {
    String email = normalizeEmail(rawEmail);
    if (email == null || email.length() > 254 || RegexUtils.isEmailInvalid(email)) {
      return Result.fail("Invalid email address.");
    }
    if (!rateLimiter.allow("code-email:" + email, 1, 60000)) {
      return Result.fail("Please wait a minute before requesting another code.");
    }
    String code = RandomCodes.digits(6);
    stringRedisTemplate
        .opsForValue()
        .set(LOGIN_CODE_KEY + email, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
    if (!loginCodeSender.send(email, code)) {
      stringRedisTemplate.delete(LOGIN_CODE_KEY + email);
      return Result.fail("Code delivery is not configured.");
    }
    return Result.ok();
  }

  @Override
  public Result login(LoginFormDTO loginForm) {
    String email = normalizeEmail(loginForm.getEmail());
    if (email == null || email.length() > 254 || RegexUtils.isEmailInvalid(email)) {
      return Result.fail("Invalid email address.");
    }
    if (!rateLimiter.allow("login-email:" + email, 5, 120000)) {
      return Result.fail("Too many attempts. Please try again later.");
    }
    String code = loginForm.getCode();
    if (code == null
        || !code.matches("[0-9]{6}")
        || !Long.valueOf(1)
            .equals(
                stringRedisTemplate.execute(
                    CONSUME_CODE, Collections.singletonList(LOGIN_CODE_KEY + email), code))) {
      return Result.fail("Incorrect or already used code.");
    }
    User user = userRepository.findByEmail(email);
    if (user == null) {
      try {
        user = createUserWithEmail(email);
      } catch (DataIntegrityViolationException duplicate) {
        // A concurrent first login created the same email; the unique key decides the winner.
        user = userRepository.findByEmail(email);
      }
    }
    TokenPairDTO tokens = tokenService.issue(UserDTO.from(user));
    return Result.ok(tokens);
  }

  @Override
  public Result refresh(String refreshToken) {
    TokenPairDTO tokens = tokenService.refresh(refreshToken);
    return tokens == null
        ? Result.fail("Your session has expired. Please log in again.")
        : Result.ok(tokens);
  }

  @Override
  public Result signInToday() {
    // 1. Get the current logged-in user
    Long userId = UserHolder.getUser().getId();
    // 2. Get the date
    LocalDateTime now = LocalDateTime.now();
    // 3. Build the key
    String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
    String key = USER_SIGN_KEY + userId + keySuffix;
    // 4. Get today's day of the month
    int dayOfMonth = now.getDayOfMonth();
    // 5. Write to Redis: SETBIT key offset 1
    stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
    return Result.ok();
  }

  /**
   * Counts the consecutive check-in days this month up to today, not the month's total check-ins.
   *
   * <p>Counts consecutive 1s starting from the lowest bit (today); returns 0 if there is no
   * check-in today, and never spans months.
   */
  @Override
  public Result queryCurrentMonthConsecutiveSignInDays() {
    // 1. Get the current logged-in user
    Long userId = UserHolder.getUser().getId();
    // 2. Get the date
    LocalDateTime now = LocalDateTime.now();
    // 3. Build the key
    String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
    String key = USER_SIGN_KEY + userId + keySuffix;
    // 4. Get today's day of the month
    int dayOfMonth = now.getDayOfMonth();
    // 5. Get this month's check-ins up to today as one decimal number:
    // BITFIELD sign:5:202203 GET u14 0
    List<Long> bitmapValues =
        stringRedisTemplate
            .opsForValue()
            .bitField(
                key,
                BitFieldSubCommands.create()
                    .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                    .valueAt(0));
    if (bitmapValues == null || bitmapValues.isEmpty()) {
      // No check-ins at all
      return Result.ok(0);
    }
    Long signInBitmap = bitmapValues.get(0);
    if (signInBitmap == null || signInBitmap == 0) {
      return Result.ok(0);
    }
    // 6. Loop over the bits
    int consecutiveDays = 0;
    while (true) {
      // 6.1. AND the number with 1 to get its last bit and check whether that bit is 0
      if ((signInBitmap & 1) == 0) {
        // 0 means no check-in; stop
        break;
      } else {
        // Non-zero means checked in; increment the counter
        consecutiveDays++;
      }
      // Shift right by one to drop the last bit and move on to the next
      signInBitmap >>>= 1;
    }
    return Result.ok(consecutiveDays);
  }

  private User createUserWithEmail(String email) {
    User user = new User();
    user.setEmail(email);
    user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomCodes.lowerAlphanumeric(10));
    userRepository.insert(user);
    return user;
  }

  @Override
  public Result logout(Jwt accessToken, String refreshToken) {
    if (accessToken == null) {
      return Result.fail("Missing login token.");
    }
    tokenService.revoke(accessToken, refreshToken);
    UserHolder.removeUser();
    return Result.ok();
  }

  @Override
  public Result queryUserById(Long userId) {
    User user = getById(userId);
    return user == null ? Result.ok() : Result.ok(UserDTO.from(user));
  }
}
