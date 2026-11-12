package com.hmdp.repository;

import com.hmdp.entity.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for user profiles.
 *
 * @since 2021-12-24
 */
public interface UserInfoRepository
    extends JpaRepository<UserInfo, Long>, InsertRepository<UserInfo> {}
