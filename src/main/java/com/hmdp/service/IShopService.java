package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.entity.Shop;
import java.util.List;

/**
 * Shop service.
 *
 * @since 2021-12-22
 */
public interface IShopService {
  Shop getById(Long id);

  List<Shop> list();

  Result queryById(Long id);

  Result update(Shop shop);

  Result queryShopByType(Integer typeId, Integer current, Double x, Double y);

  Result searchShops(ShopSearchDTO criteria);

  Result catalog(String keyword, Long typeId, Integer page, Integer size, String sort);

  Result queryShopsByIds(List<Long> ids);

  Result listAreas();

  Result saveShop(Shop shop);

  Result queryShopByName(String name, Integer current);

  Result queryShopDetail(Long id);
}
