package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-24
 */
public interface IUserInfoService {

  Result queryUserInfo(Long userId);
}
