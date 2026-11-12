package com.hmdp.controller;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.RefreshTokenDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端控制器
 *
 * @author 虎哥
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

  @Resource
  private IUserService userService;

  @Resource
  private IUserInfoService userInfoService;

  /** 发送邮箱验证码（一次性，2 分钟有效）。 */
  @PostMapping("code")
  public Result sendCode(@RequestParam("email") String email) {
    return userService.sendCode(email);
  }

  /** 邮箱验证码登录；首次登录自动注册。返回 access token（JWT）与一次性 refresh token。 */
  @PostMapping("/login")
  public Result login(@RequestBody LoginFormDTO loginForm) {
    return userService.login(loginForm);
  }

  /** 用 refresh token 换取新的令牌对；旧 refresh token 随即失效。 */
  @PostMapping("/refresh")
  public Result refresh(@RequestBody RefreshTokenDTO request) {
    return userService.refresh(request == null ? null : request.getRefreshToken());
  }

  /** 吊销当前 access token 与（可选）refresh token。 */
  @PostMapping("/logout")
  public Result logout(
      @AuthenticationPrincipal Jwt accessToken,
      @RequestBody(required = false) RefreshTokenDTO request) {
    return userService.logout(accessToken, request == null ? null : request.getRefreshToken());
  }

  @GetMapping("/me")
  public Result me() {
    // 获取当前登录的用户并返回
    UserDTO user = UserHolder.getUser();
    return Result.ok(user);
  }

  @GetMapping("/info/{id}")
  public Result info(@PathVariable("id") Long userId) {
    return userInfoService.queryUserInfo(userId);
  }

  @GetMapping("/{id}")
  public Result queryUserById(@PathVariable("id") Long userId) {
    return userService.queryUserById(userId);
  }

  @PostMapping("/sign")
  public Result signInToday() {
    return userService.signInToday();
  }

  @GetMapping("/sign/count")
  public Result queryCurrentMonthConsecutiveSignInDays() {
    return userService.queryCurrentMonthConsecutiveSignInDays();
  }
}
