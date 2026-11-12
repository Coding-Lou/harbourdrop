package com.hmdp.utils;

/**
 * Summarizes persisted failure reasons so infrastructure doesn't depend on a business service
 * implementation class.
 */
public final class FailureMessages {
  private static final int MAX_REASON_LENGTH = 500;

  private FailureMessages() {}

  public static String summarize(Throwable failure) {
    String text = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
    return text.substring(0, Math.min(MAX_REASON_LENGTH, text.length()));
  }
}
