package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.repository.SeckillVoucherRepository;
import com.hmdp.service.ISeckillVoucherService;
import jakarta.annotation.Resource;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Service for the seckill voucher table, which is one-to-one with the voucher table.
 *
 * @since 2022-01-04
 */
@Service
public class SeckillVoucherServiceImpl implements ISeckillVoucherService {
  @Resource
  private SeckillVoucherRepository seckillVoucherRepository;

  @Override
  public SeckillVoucher getById(Long voucherId) {
    return voucherId == null ? null : seckillVoucherRepository.findById(voucherId).orElse(null);
  }

  @Override
  public List<SeckillVoucher> listByIds(Collection<Long> voucherIds) {
    return seckillVoucherRepository.findAllById(voucherIds);
  }

  @Override
  public void save(SeckillVoucher voucher) {
    seckillVoucherRepository.insert(voucher);
  }

  @Override
  public boolean deductStock(Long voucherId) {
    return seckillVoucherRepository.deductStock(voucherId) > 0;
  }
}
