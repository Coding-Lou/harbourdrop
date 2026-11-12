package com.hmdp.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Delivers one-time login codes by email. Local development points SMTP at Mailpit; when mail is
 * disabled (tests, offline runs) the code is only written to the debug log.
 */
@Slf4j
@Component
public class LoginCodeSender {
  private final ObjectProvider<JavaMailSender> mailSender;

  @Value("${hmdp.mail.enabled:false}")
  private boolean enabled;

  @Value("${hmdp.mail.from:no-reply@hmdp.local}")
  private String from;

  public LoginCodeSender(ObjectProvider<JavaMailSender> mailSender) {
    this.mailSender = mailSender;
  }

  public void send(String email, String code) {
    JavaMailSender sender = enabled ? mailSender.getIfAvailable() : null;
    if (sender == null) {
      log.debug("Mail disabled; login code for {} is {}", email, code);
      return;
    }
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(email);
    message.setSubject("Your HMDP sign-in code");
    message.setText("Your sign-in code is " + code + ". It expires in 2 minutes.");
    sender.send(message);
  }
}
