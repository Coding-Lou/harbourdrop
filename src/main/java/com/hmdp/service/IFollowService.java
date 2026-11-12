package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService {

  Result follow(Long followUserId, Boolean isFollow);

  Result isFollow(Long followUserId);

  Result followCommons(Long id);
}
