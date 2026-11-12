package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.repository.SeckillVoucherRepository;
import com.hmdp.service.ISeckillVoucherService;
import jakarta.annotation.Resource;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 *
 * @author 虎哥
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
