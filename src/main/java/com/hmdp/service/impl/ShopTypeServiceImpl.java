package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.repository.ShopTypeRepository;
import com.hmdp.service.IShopTypeService;
import jakarta.annotation.Resource;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl implements IShopTypeService {
  @Resource
  private ShopTypeRepository shopTypeRepository;

  @Override
  public Result queryTypeList() {
    List<ShopType> typeList = shopTypeRepository.findAll(Sort.by("sort"));
    return Result.ok(typeList);
  }
}
