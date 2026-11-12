package com.hmdp.security;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Copies the authenticated JWT identity into {@link UserHolder}, which the business layer reads.
 * The holder is always cleared afterwards so pooled threads never leak a user between requests.
 */
public class UserContextFilter extends OncePerRequestFilter {

  static UserDTO toUser(Jwt jwt) {
    UserDTO user = new UserDTO();
    user.setId(Long.valueOf(jwt.getSubject()));
    user.setNickName(jwt.getClaimAsString("nickName"));
    user.setIcon(jwt.getClaimAsString("icon"));
    return user;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      UserHolder.saveUser(toUser(jwt));
    }
    try {
      chain.doFilter(request, response);
    } finally {
      UserHolder.removeUser();
    }
  }
}
