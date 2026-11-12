package com.hmdp.utils;

import java.security.SecureRandom;

/** Random strings for verification codes and generated names. */
public final class RandomCodes {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String DIGITS = "0123456789";
  private static final String LOWER_ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789";

  private RandomCodes() {}

  public static String digits(int length) {
    return pick(DIGITS, length);
  }

  public static String lowerAlphanumeric(int length) {
    return pick(LOWER_ALPHANUMERIC, length);
  }

  private static String pick(String alphabet, int length) {
    StringBuilder value = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      value.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
    }
    return value.toString();
  }
}
