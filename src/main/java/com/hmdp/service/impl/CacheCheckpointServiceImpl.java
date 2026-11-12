package com.hmdp.service.impl;

import com.hmdp.repository.CacheCheckpointRepository;
import com.hmdp.service.ICacheCheckpointService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class CacheCheckpointServiceImpl implements ICacheCheckpointService {
  @Resource
  private CacheCheckpointRepository cacheCheckpointRepository;

  @Override
  public void saveCheckpoint(
      String destination, Long batchId, String binlogFile, Long binlogOffset, Long eventTimeMs) {
    cacheCheckpointRepository.saveCheckpoint(
        destination, batchId, binlogFile, binlogOffset, eventTimeMs);
  }
}
