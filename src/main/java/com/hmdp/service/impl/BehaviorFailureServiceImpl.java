package com.hmdp.service.impl;

import com.hmdp.repository.BehaviorRepository;
import com.hmdp.service.IBehaviorFailureService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class BehaviorFailureServiceImpl implements IBehaviorFailureService {
  @Resource
  private BehaviorRepository behaviorRepository;

  @Override
  public void saveFailure(
      String topic, Integer partitionId, Long messageOffset, String payload, String reason) {
    behaviorRepository.insertFailure(topic, partitionId, messageOffset, payload, reason);
  }
}
