package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * Follow service.
 *
 * @since 2021-12-22
 */
public interface IFollowService {

  Result follow(Long followUserId, Boolean isFollow);

  Result isFollow(Long followUserId);

  Result followCommons(Long id);
}
