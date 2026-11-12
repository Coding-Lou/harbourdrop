package com.hmdp.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Delivers one-time login codes by email (Mailpit in local development).
 *
 * <p>Without SMTP it fails closed: a code is never written to logs unless the operator explicitly
 * sets {@code hmdp.mail.log-codes=true}, which is meant only for an isolated demo host.
 */
@Slf4j
@Component
public class LoginCodeSender {
  private final ObjectProvider<JavaMailSender> mailSender;

  @Value("${hmdp.mail.enabled:false}")
  private boolean enabled;

  @Value("${hmdp.mail.log-codes:false}")
  private boolean logCodes;

  @Value("${hmdp.mail.from:no-reply@harbourdrop.local}")
  private String from;

  public LoginCodeSender(ObjectProvider<JavaMailSender> mailSender) {
    this.mailSender = mailSender;
  }

  /** Returns false when the code could not be delivered by any configured channel. */
  public boolean send(String email, String code) {
    JavaMailSender sender = enabled ? mailSender.getIfAvailable() : null;
    if (sender != null) {
      SimpleMailMessage message = new SimpleMailMessage();
      message.setFrom(from);
      message.setTo(email);
      message.setSubject("Your HarbourDrop sign-in code");
      message.setText("Your sign-in code is " + code + ". It expires in 2 minutes.");
      sender.send(message);
      return true;
    }
    if (logCodes) {
      log.warn("DEMO ONLY (hmdp.mail.log-codes=true): login code for {} is {}", mask(email), code);
      return true;
    }
    return false;
  }

  static String mask(String email) {
    int at = email.indexOf('@');
    if (at <= 1) {
      return "***" + email.substring(Math.max(at, 0));
    }
    return email.charAt(0) + "***" + email.substring(at);
  }
}
