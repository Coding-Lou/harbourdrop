package com.hmdp.repository;

import com.hmdp.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long>, InsertRepository<User> {
  User findByPhone(String phone);
}
