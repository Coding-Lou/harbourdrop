package com.hmdp.service;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import jakarta.servlet.http.HttpSession;
import java.util.Collection;
import java.util.List;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService {
  User getById(Long id);

  List<User> listByIds(Collection<Long> ids);

  Result sendCode(String phone, HttpSession session);

  Result login(LoginFormDTO loginForm, HttpSession session);

  Result signInToday();

  Result queryCurrentMonthConsecutiveSignInDays();

  Result logout(String token);

  Result queryUserById(Long userId);
}
