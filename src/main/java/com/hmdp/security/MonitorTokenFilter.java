package com.hmdp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets Prometheus scrape {@code GET /actuator/prometheus} with a static bearer credential. The
 * credential grants only {@code ROLE_MONITOR}; it is never accepted as a user login.
 */
public class MonitorTokenFilter extends OncePerRequestFilter {
  static final String METRICS_PATH = "/actuator/prometheus";
  private final String monitorToken;

  public MonitorTokenFilter(String monitorToken) {
    this.monitorToken = monitorToken == null ? "" : monitorToken;
  }

  /** True when the header carries the monitor credential, so it must not be parsed as a JWT. */
  boolean isMonitorCredential(String authorization) {
    return !monitorToken.isEmpty()
        && authorization != null
        && MessageDigest.isEqual(
            ("Bearer " + monitorToken).getBytes(StandardCharsets.UTF_8),
            authorization.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ("GET".equals(request.getMethod())
        && METRICS_PATH.equals(request.getServletPath())
        && isMonitorCredential(request.getHeader("Authorization"))) {
      SecurityContextHolder.getContext()
          .setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  "prometheus", null, List.of(new SimpleGrantedAuthority("ROLE_MONITOR"))));
    }
    chain.doFilter(request, response);
  }
}
