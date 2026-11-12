package com.hmdp.service;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService {
  User getById(Long id);

  List<User> listByIds(Collection<Long> ids);

  Result sendCode(String email);

  Result login(LoginFormDTO loginForm);

  Result refresh(String refreshToken);

  Result signInToday();

  Result queryCurrentMonthConsecutiveSignInDays();

  Result logout(Jwt accessToken, String refreshToken);

  Result queryUserById(Long userId);
}
