package com.hmdp.repository;

import com.hmdp.entity.BlogComments;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlogCommentsRepository
    extends JpaRepository<BlogComments, Long>, InsertRepository<BlogComments> {}
