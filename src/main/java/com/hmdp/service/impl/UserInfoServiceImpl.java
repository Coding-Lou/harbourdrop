package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.UserInfo;
import com.hmdp.repository.UserInfoRepository;
import com.hmdp.service.IUserInfoService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl implements IUserInfoService {
  @Resource
  private UserInfoRepository userInfoRepository;

  @Override
  public Result queryUserInfo(Long userId) {
    UserInfo info = userId == null ? null : userInfoRepository.findById(userId).orElse(null);
    if (info == null) {
      return Result.ok();
    }
    info.setCreateTime(null);
    info.setUpdateTime(null);
    return Result.ok(info);
  }
}
