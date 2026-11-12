package com.hmdp.repository;

import com.hmdp.entity.Shop;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShopRepository
    extends JpaRepository<Shop, Long>, JpaSpecificationExecutor<Shop>, InsertRepository<Shop> {

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_shop
          WHERE id=:shopId
          """,
      nativeQuery = true)
  Long countByShopId(@Param("shopId") Long shopId);

  @Query(
      value = "SELECT DISTINCT area FROM tb_shop WHERE area IS NOT NULL AND area <> ''",
      nativeQuery = true)
  List<String> selectDistinctAreas();
}
