package com.hmdp.reliability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IOperationAuditService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

@Component
public class AccessGuard implements HandlerInterceptor {
  private static final ObjectMapper JSON =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

  @Resource
  private IOperationAuditService operationAuditService;

  private final RateLimiter limits;

  public AccessGuard(RateLimiter limits) {
    this.limits = limits;
  }

  /**
   * Rate limits and request hygiene. Authentication and role checks run earlier, in the Spring
   * Security filter chain ({@code SecurityConfig}); by the time this interceptor runs, protected
   * routes already have an authenticated {@link UserHolder} user.
   */
  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
      throws Exception {
    // The original request was already checked; let the error page render its real status.
    if (req.getDispatcherType() == DispatcherType.ERROR) {
      return true;
    }
    // Defense in depth next to the Security firewall: reject URIs whose raw form differs from the
    // path used for routing (";params", "%xx", "//", dot segments).
    if (isAmbiguousUri(req.getRequestURI())) {
      return reject(res, 400, "请求路径无效");
    }
    String path = UrlPathHelper.defaultInstance.getLookupPathForRequest(req);
    boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
    if (path.startsWith("/experience/")
        && !limits.allow("experience-ip:" + req.getRemoteAddr(), 60, 60000)) {
      return reject(res, 429, "体验请求较多，请稍后重试");
    }
    boolean login =
        path.equals("/user/code") || path.equals("/user/login") || path.equals("/user/refresh");
    if (login && !limits.allow("auth-ip:" + req.getRemoteAddr(), 30, 60000)) {
      return reject(res, 429, "请求过于频繁");
    }
    UserDTO user = UserHolder.getUser();
    if (user != null && path.startsWith("/voucher-order/seckill/") && !read) {
      if (!limits.allow("claim-user:" + user.getId(), 10, 1000)
          || !limits.allow("claim-global", 1000, 1000)) {
        return reject(res, 429, "抢购繁忙，请稍后重试");
      }
    }
    if (user != null
        && read
        && path.startsWith("/voucher-order/")
        && !limits.allow("order-read:" + user.getId(), 30, 1000)) {
      return reject(res, 429, "查询过于频繁");
    }
    return true;
  }

  static boolean isAmbiguousUri(String uri) {
    return uri == null
        || uri.indexOf(';') >= 0
        || uri.indexOf('%') >= 0
        || uri.indexOf('\\') >= 0
        || uri.contains("//")
        || uri.contains("/./")
        || uri.contains("/../")
        || uri.endsWith("/.")
        || uri.endsWith("/..");
  }

  private boolean reject(HttpServletResponse res, int status, String text) throws Exception {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write(JSON.writeValueAsString(Result.fail(text)));
    return false;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
    if (!(req.getRequestURI().startsWith("/ops/") || req.getRequestURI().equals("/shop"))
        || "GET".equals(req.getMethod())) {
      return;
    }
    // No request body, authentication token or personal text is stored in the audit.
    operationAuditService.recordAudit(
        UserHolder.getUser() == null ? null : UserHolder.getUser().getId(),
        req.getMethod(),
        req.getRequestURI(),
        res.getStatus());
  }
}
