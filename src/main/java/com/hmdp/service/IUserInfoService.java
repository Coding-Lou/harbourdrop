package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * User profile service.
 *
 * @since 2021-12-24
 */
public interface IUserInfoService {

  Result queryUserInfo(Long userId);
}
