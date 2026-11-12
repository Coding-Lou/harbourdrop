package com.hmdp.repository;

import com.hmdp.entity.ShopType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopTypeRepository
    extends JpaRepository<ShopType, Long>, InsertRepository<ShopType> {}
