package com.hmdp.service;

import com.hmdp.entity.BusinessOutboxTask;
import com.hmdp.messaging.ProjectionResult;

/**
 * Projects durable business facts onto caches, resources, or follow-up tasks. Callers own message
 * identity and the consuming transaction.
 */
public interface IBusinessProjectionService {
  /** CONTINUE means this batch succeeded with more to come; failures throw to keep retrying. */
  ProjectionResult project(BusinessOutboxTask task);
}
