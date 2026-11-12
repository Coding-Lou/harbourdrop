package com.hmdp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.repository.VoucherOrderRepository;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class VoucherOrderReliabilityTest {
  static class Service extends VoucherOrderServiceImpl {
    VoucherOrder existing;
    VoucherOrderRepository orders = mock(VoucherOrderRepository.class);

    Service() {
      ReflectionTestUtils.setField(this, "voucherOrderRepository", orders);
    }

    @Override
    public VoucherOrder getById(Long id) {
      return existing;
    }

    void consume(VoucherOrder order) {
      createVoucherOrder(order);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void persistenceFailureRollsBackAndDuplicateDeliveryDoesNotDeductAgain() {
    Service service = new Service();
    RLock lock = mock(RLock.class);
    when(lock.tryLock()).thenReturn(true);
    RedissonClient redis = mock(RedissonClient.class);
    when(redis.getLock(anyString())).thenReturn(lock);
    ISeckillVoucherService stock = mock(ISeckillVoucherService.class);
    when(stock.deductStock(9L)).thenReturn(true);
    IVoucherService vouchers = mock(IVoucherService.class);
    when(vouchers.getById(9L)).thenReturn(new Voucher().setId(9L).setPayValue(0L));
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    TransactionStatus status = mock(TransactionStatus.class);
    when(manager.getTransaction(any())).thenReturn(status);
    ReflectionTestUtils.setField(service, "redissonClient", redis);
    ReflectionTestUtils.setField(service, "seckillVoucherService", stock);
    ReflectionTestUtils.setField(service, "voucherService", vouchers);
    ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(manager));
    VoucherOrder order = new VoucherOrder().setId(88L).setUserId(7L).setVoucherId(9L);
    when(service.orders.insert(any()))
        .thenThrow(new DataIntegrityViolationException("simulated insert failure"));
    assertThrows(DataIntegrityViolationException.class, () -> service.consume(order));
    verify(manager).rollback(status);
    verify(lock).unlock();
    reset(stock, manager);
    when(manager.getTransaction(any())).thenReturn(status);
    service.existing = order;
    service.consume(order);
    verifyNoInteractions(stock);
    verify(manager).commit(status);
  }

  @Test
  void lockContentionFailsConsumptionInsteadOfAcknowledgingSuccess() {
    Service service = new Service();
    RLock lock = mock(RLock.class);
    RedissonClient redis = mock(RedissonClient.class);
    when(redis.getLock(anyString())).thenReturn(lock);
    ReflectionTestUtils.setField(service, "redissonClient", redis);
    assertThrows(
        IllegalStateException.class,
        () -> service.consume(new VoucherOrder().setId(88L).setUserId(7L).setVoucherId(9L)));
    verify(lock, never()).unlock();
  }

  @Test
  @SuppressWarnings("unchecked")
  void personalReadsAlwaysUseAuthenticatedOwnerAndHeldStatus() {
    Service service = new Service();
    UserDTO user = new UserDTO();
    user.setId(7L);
    UserHolder.saveUser(user);
    when(service.orders.findByUserIdAndStatus(eq(7L), eq(2), any(Pageable.class)))
        .thenReturn(Page.empty());
    try {
      assertTrue(service.queryMyOrders(1, true).getSuccess());
      // Owner comes from the session and "held" means PAID (status 2).
      verify(service.orders).findByUserIdAndStatus(eq(7L), eq(2), any(Pageable.class));
      verify(service.orders, never()).findByUserId(any(), any());
      assertFalse(service.queryMyOrders(0, false).getSuccess());
    } finally {
      UserHolder.removeUser();
    }
  }
}
