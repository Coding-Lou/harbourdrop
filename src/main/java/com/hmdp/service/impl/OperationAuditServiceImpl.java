package com.hmdp.service.impl;

import com.hmdp.repository.OperationAuditRepository;
import com.hmdp.service.IOperationAuditService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class OperationAuditServiceImpl implements IOperationAuditService {
  @Resource
  private OperationAuditRepository operationAuditRepository;

  @Override
  public void recordAudit(Long userId, String method, String path, Integer httpStatus) {
    operationAuditRepository.insertAudit(userId, method, path, httpStatus);
  }
}
