package com.hmdp.messaging;

/**
 * Projection result; CONTINUE means this batch succeeded but the next batch still has to be
 * durably registered.
 */
public enum ProjectionResult {
  COMPLETE,
  CONTINUE
}
