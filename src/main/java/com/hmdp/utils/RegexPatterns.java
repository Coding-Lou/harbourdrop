package com.hmdp.utils;

public abstract class RegexPatterns {
  /** Phone number pattern */
  public static final String PHONE_REGEX =
      "^1([38][0-9]|4[579]|5[0-3,5-9]|6[6]|7[0135678]|9[89])\\d{8}$";

  /** Email pattern */
  public static final String EMAIL_REGEX =
      "^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$";

  /** Password pattern: 4-32 letters, digits, or underscores */
  public static final String PASSWORD_REGEX = "^\\w{4,32}$";

  /** Verification code pattern: 6 letters or digits */
  public static final String VERIFY_CODE_REGEX = "^[a-zA-Z\\d]{6}$";
}
