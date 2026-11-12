package com.hmdp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

class LoginCodeSenderTest {
  @Test
  @SuppressWarnings("unchecked")
  void withoutMailItFailsClosedUnlessLoggingIsExplicitlyAllowed() {
    LoginCodeSender sender = new LoginCodeSender(mock(ObjectProvider.class));
    assertFalse(sender.send("someone@example.com", "123456"));
    ReflectionTestUtils.setField(sender, "logCodes", true);
    assertTrue(sender.send("someone@example.com", "123456"));
  }

  @Test
  void logLinesNeverContainTheFullAddress() {
    assertEquals("s***@example.com", LoginCodeSender.mask("someone@example.com"));
    assertEquals("***@example.com", LoginCodeSender.mask("a@example.com"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void mailIsUsedWhenEnabled() {
    ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    JavaMailSender mail = mock(JavaMailSender.class);
    when(provider.getIfAvailable()).thenReturn(mail);
    LoginCodeSender sender = new LoginCodeSender(provider);
    ReflectionTestUtils.setField(sender, "enabled", true);
    assertTrue(sender.send("someone@example.com", "123456"));
    verify(mail).send(any(SimpleMailMessage.class));
  }
}
