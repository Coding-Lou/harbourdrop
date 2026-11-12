package com.hmdp.utils;

public class RegexUtils {
  /**
   * Whether the phone number format is invalid.
   *
   * @param phone phone number to check
   * @return true if invalid, false otherwise
   */
  public static boolean isPhoneInvalid(String phone) {
    return mismatch(phone, RegexPatterns.PHONE_REGEX);
  }

  /**
   * Whether the email format is invalid.
   *
   * @param email email to check
   * @return true if invalid, false otherwise
   */
  public static boolean isEmailInvalid(String email) {
    return mismatch(email, RegexPatterns.EMAIL_REGEX);
  }

  /**
   * Whether the verification code format is invalid.
   *
   * @param code verification code to check
   * @return true if invalid, false otherwise
   */
  public static boolean isCodeInvalid(String code) {
    return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
  }

  // Check whether the string fails to match the pattern
  private static boolean mismatch(String str, String regex) {
    if (str == null || str.isBlank()) {
      return true;
    }
    return !str.matches(regex);
  }
}
