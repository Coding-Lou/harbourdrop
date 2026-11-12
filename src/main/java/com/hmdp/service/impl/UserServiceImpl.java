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

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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
      return Result.fail("邮箱格式错误！");
    }
    if (!rateLimiter.allow("code-email:" + email, 1, 60000)) {
      return Result.fail("请一分钟后再获取验证码");
    }
    String code = RandomCodes.digits(6);
    stringRedisTemplate
        .opsForValue()
        .set(LOGIN_CODE_KEY + email, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
    loginCodeSender.send(email, code);
    return Result.ok();
  }

  @Override
  public Result login(LoginFormDTO loginForm) {
    String email = normalizeEmail(loginForm.getEmail());
    if (email == null || email.length() > 254 || RegexUtils.isEmailInvalid(email)) {
      return Result.fail("邮箱格式错误！");
    }
    if (!rateLimiter.allow("login-email:" + email, 5, 120000)) {
      return Result.fail("尝试次数过多，请稍后重试");
    }
    String code = loginForm.getCode();
    if (code == null
        || !code.matches("[0-9]{6}")
        || !Long.valueOf(1)
            .equals(
                stringRedisTemplate.execute(
                    CONSUME_CODE, Collections.singletonList(LOGIN_CODE_KEY + email), code))) {
      return Result.fail("验证码错误或已使用");
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
    return tokens == null ? Result.fail("登录已过期，请重新登录") : Result.ok(tokens);
  }

  @Override
  public Result signInToday() {
    // 1.获取当前登录用户
    Long userId = UserHolder.getUser().getId();
    // 2.获取日期
    LocalDateTime now = LocalDateTime.now();
    // 3.拼接key
    String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
    String key = USER_SIGN_KEY + userId + keySuffix;
    // 4.获取今天是本月的第几天
    int dayOfMonth = now.getDayOfMonth();
    // 5.写入Redis SETBIT key offset 1
    stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
    return Result.ok();
  }

  /**
   * 统计本月截至今天连续签到的天数，不是本月累计签到次数。
   *
   * <p>从今天对应的最低位开始数连续的 1；今天未签到返回 0，统计不会跨越月份。
   */
  @Override
  public Result queryCurrentMonthConsecutiveSignInDays() {
    // 1.获取当前登录用户
    Long userId = UserHolder.getUser().getId();
    // 2.获取日期
    LocalDateTime now = LocalDateTime.now();
    // 3.拼接key
    String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
    String key = USER_SIGN_KEY + userId + keySuffix;
    // 4.获取今天是本月的第几天
    int dayOfMonth = now.getDayOfMonth();
    // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
    List<Long> bitmapValues =
        stringRedisTemplate
            .opsForValue()
            .bitField(
                key,
                BitFieldSubCommands.create()
                    .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                    .valueAt(0));
    if (bitmapValues == null || bitmapValues.isEmpty()) {
      // 没有任何签到结果
      return Result.ok(0);
    }
    Long signInBitmap = bitmapValues.get(0);
    if (signInBitmap == null || signInBitmap == 0) {
      return Result.ok(0);
    }
    // 6.循环遍历
    int consecutiveDays = 0;
    while (true) {
      // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
      if ((signInBitmap & 1) == 0) {
        // 如果为0，说明未签到，结束
        break;
      } else {
        // 如果不为0，说明已签到，计数器+1
        consecutiveDays++;
      }
      // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
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
      return Result.fail("缺少登录令牌");
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
