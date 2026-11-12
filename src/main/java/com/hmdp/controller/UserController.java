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
 * REST controller for users and sign-in.
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

  @Resource
  private IUserService userService;

  @Resource
  private IUserInfoService userInfoService;

  /** Sends a one-time email verification code, valid for 2 minutes. */
  @PostMapping("code")
  public Result sendCode(@RequestParam("email") String email) {
    return userService.sendCode(email);
  }

  /**
   * Signs in with an email code, registering on first sign-in. Returns an access token (JWT) and a
   * single-use refresh token.
   */
  @PostMapping("/login")
  public Result login(@RequestBody LoginFormDTO loginForm) {
    return userService.login(loginForm);
  }

  /** Exchanges a refresh token for a new token pair; the old refresh token stops working. */
  @PostMapping("/refresh")
  public Result refresh(@RequestBody RefreshTokenDTO request) {
    return userService.refresh(request == null ? null : request.getRefreshToken());
  }

  /** Revokes the current access token and, optionally, the refresh token. */
  @PostMapping("/logout")
  public Result logout(
      @AuthenticationPrincipal Jwt accessToken,
      @RequestBody(required = false) RefreshTokenDTO request) {
    return userService.logout(accessToken, request == null ? null : request.getRefreshToken());
  }

  @GetMapping("/me")
  public Result me() {
    // Return the currently signed-in user
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
