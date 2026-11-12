package com.hmdp.repository;

import com.hmdp.entity.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 数据访问接口
 *
 * @author 虎哥
 * @since 2021-12-24
 */
public interface UserInfoRepository
    extends JpaRepository<UserInfo, Long>, InsertRepository<UserInfo> {}
