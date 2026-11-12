package com.hmdp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class CacheChangeProjectorTest {
  @Test
  void replayReadsLatestDatabaseStateInsteadOfWritingOldEventPayload() {
    CacheClient cache = mock(CacheClient.class);
    ObjectProvider<IShopService> provider = mock(ObjectProvider.class);
    IShopService shops = mock(IShopService.class);
    when(provider.getObject()).thenReturn(shops);
    when(shops.getById(5L)).thenReturn(new Shop().setId(5L).setName("latest"));
    when(cache.queryWithMutex(
            eq("cache:shop:"), eq(5L), eq(Shop.class), any(), eq(20L), eq(TimeUnit.MINUTES)))
        .thenAnswer(
            invocation -> {
              Function<Long, Shop> read = invocation.getArgument(3);
              assertEquals("latest", read.apply(5L).getName());
              return read.apply(5L);
            });
    CacheChangeProjector projector =
        new CacheChangeProjector(
            cache, provider, mock(ObjectProvider.class), new SimpleMeterRegistry());
    ReflectionTestUtils.setField(projector, "schema", "test");
    CdcRowChange row =
        change(
            "tb_shop", CdcRowChange.Operation.UPDATE, Map.of(), Map.of("id", "5", "name", "old"));
    projector.projectRowChange(row);
    projector.projectRowChange(row);
    verify(cache, times(2)).invalidate("cache:shop:5");
    verify(cache, times(2))
        .queryWithMutex(
            eq("cache:shop:"), eq(5L), eq(Shop.class), any(), eq(20L), eq(TimeUnit.MINUTES));
  }

  @Test
  void deletesOnlyInvalidateAndStockNeverChangesExecutionInventory() {
    CacheClient cache = mock(CacheClient.class);
    CacheChangeProjector projector =
        new CacheChangeProjector(
            cache,
            mock(ObjectProvider.class),
            mock(ObjectProvider.class),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(projector, "schema", "test");
    projector.projectRowChange(
        change("tb_shop", CdcRowChange.Operation.DELETE, Map.of("id", "5"), Map.of()));
    verify(cache).invalidate("cache:shop:5");
    projector.projectRowChange(
        change(
            "tb_seckill_voucher",
            CdcRowChange.Operation.UPDATE,
            Map.of(),
            Map.of("voucher_id", "5", "stock", "999")));
    // Rows from another database are ignored even when the table name matches.
    projector.projectRowChange(
        new CdcRowChange(
            "other", "tb_shop", CdcRowChange.Operation.UPDATE, Map.of(), Map.of("id", "6")));
    verifyNoMoreInteractions(cache);
  }

  @Test
  void ddlInvalidatesMetadataNamespacesAndMalformedIdentityFailsForReplay() {
    CacheClient cache = mock(CacheClient.class);
    CacheChangeProjector projector =
        new CacheChangeProjector(
            cache,
            mock(ObjectProvider.class),
            mock(ObjectProvider.class),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(projector, "schema", "test");
    projector.projectSchemaChange("test", "tb_voucher");
    verify(cache).invalidatePrefix("cache:voucher-meta:");
    verify(cache).invalidatePrefix("cache:voucher-list:");
    assertThrows(
        IllegalStateException.class,
        () ->
            projector.projectRowChange(
                change("tb_shop", CdcRowChange.Operation.UPDATE, Map.of(), Map.of())));
  }

  private static CdcRowChange change(
      String table,
      CdcRowChange.Operation operation,
      Map<String, String> before,
      Map<String, String> after) {
    return new CdcRowChange("test", table, operation, before, after);
  }
}
