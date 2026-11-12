package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for vouchers.
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

  @Resource
  private IVoucherService voucherService;

  @GetMapping("/manage/capabilities")
  public Result capabilities() {
    return voucherService.capabilities();
  }

  /**
   * Creates a flash-sale voucher.
   *
   * @param voucher voucher details, including the flash-sale fields
   * @return voucher ID
   */
  @PostMapping("seckill")
  public Result addSeckillVoucher(@RequestBody Voucher voucher) {
    return voucherService.createSeckillVoucher(voucher);
  }

  /**
   * Creates an ordinary voucher.
   *
   * @param voucher voucher details
   * @return voucher ID
   */
  @PostMapping
  public Result addVoucher(@RequestBody Voucher voucher) {
    return voucherService.createVoucher(voucher);
  }

  /**
   * Lists a shop's vouchers.
   *
   * @param shopId shop ID
   * @return vouchers
   */
  @GetMapping("/list/{shopId}")
  public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
    return voucherService.queryVoucherOfShop(shopId);
  }
}
