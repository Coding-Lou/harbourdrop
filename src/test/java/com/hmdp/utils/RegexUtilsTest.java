package com.hmdp.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegexUtilsTest {
  @Test
  void acceptsCommonAddressesAndRejectsMalformedOnes() {
    for (String valid :
        new String[] {"a@b.co", "first.last@example.com", "user+tag@mail.example.org"}) {
      assertFalse(RegexUtils.isEmailInvalid(valid), valid);
    }
    for (String invalid :
        new String[] {"", " ", "plain", "a@b", "a@.com", "a b@c.com", "a@b.c", "@example.com"}) {
      assertTrue(RegexUtils.isEmailInvalid(invalid), invalid);
    }
  }
}
