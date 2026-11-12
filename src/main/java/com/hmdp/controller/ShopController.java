package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for shops.
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

  @Resource
  private IShopService shopService;

  /**
   * Looks up a shop by ID.
   *
   * @param id shop ID
   * @return shop details
   */
  @GetMapping("/{id}")
  public Result queryShopById(@PathVariable("id") Long id) {
    return shopService.queryShopDetail(id);
  }

  /** Read-only combined search for CityLens; rating parameters use the database's 10x scale. */
  @GetMapping("/search")
  public Result searchShops(ShopSearchDTO criteria) {
    return shopService.searchShops(criteria);
  }

  /** Cross-category shop list for the unified frontend; the agent's strict search is unchanged. */
  @GetMapping("/catalog")
  public Result catalog(
      @RequestParam(value = "keyword", defaultValue = "") String keyword,
      @RequestParam(value = "typeId", required = false) Long typeId,
      @RequestParam(value = "page", defaultValue = "1") Integer page,
      @RequestParam(value = "size", defaultValue = "12") Integer size,
      @RequestParam(value = "sort", defaultValue = "score") String sort) {
    return shopService.catalog(keyword, typeId, page, size, sort);
  }

  /** Lets CityLens re-check candidates in bulk before pairing two stops; reads live shop data. */
  @GetMapping("/batch")
  public Result queryShopsByIds(@RequestParam("ids") List<Long> ids) {
    return shopService.queryShopsByIds(ids);
  }

  /** Lets CityLens match real neighbourhoods from the user's own words instead of guessing. */
  @GetMapping("/areas")
  public Result listAreas() {
    return shopService.listAreas();
  }

  /**
   * Creates a shop.
   *
   * @param shop shop data
   * @return shop ID
   */
  @PostMapping
  public Result saveShop(@RequestBody Shop shop) {
    return shopService.saveShop(shop);
  }

  /**
   * Updates a shop.
   *
   * @param shop shop data
   */
  @PutMapping
  public Result updateShop(@RequestBody Shop shop) {
    // Write to the database
    return shopService.update(shop);
  }

  /**
   * Pages shops of a given type.
   *
   * @param typeId shop type
   * @param current page number
   * @return shops
   */
  @GetMapping("/of/type")
  public Result queryShopByType(
      @RequestParam("typeId") Integer typeId,
      @RequestParam(value = "current", defaultValue = "1") Integer current,
      @RequestParam(value = "x", required = false) Double x,
      @RequestParam(value = "y", required = false) Double y) {
    return shopService.queryShopByType(typeId, current, x, y);
  }

  /**
   * Pages shops whose name matches a keyword.
   *
   * @param name shop name keyword
   * @param current page number
   * @return shops
   */
  @GetMapping("/of/name")
  public Result queryShopByName(
      @RequestParam(value = "name", required = false) String name,
      @RequestParam(value = "current", defaultValue = "1") Integer current) {
    return shopService.queryShopByName(name, current);
  }
}
