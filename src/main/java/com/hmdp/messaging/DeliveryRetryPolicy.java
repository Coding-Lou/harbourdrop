package com.hmdp.messaging;

/**
 * Send backoff rules shared by the business and analytics outboxes; keeps the existing retry counts
 * and timing.
 */
public final class DeliveryRetryPolicy {
  public static final int MAX_SEND_ATTEMPTS = 10;
  public static final int MAX_CONSUME_ATTEMPTS = 5;
  private static final long MAX_DELAY_SECONDS = 300;
  private static final int MAX_BACKOFF_EXPONENT = 8;

  private DeliveryRetryPolicy() {}

  /** Returns seconds; caps the exponent to avoid overflow and unbounded waits. */
  public static long delaySeconds(int attempts) {
    return Math.min(MAX_DELAY_SECONDS, 1L << Math.min(attempts, MAX_BACKOFF_EXPONENT));
  }
}
