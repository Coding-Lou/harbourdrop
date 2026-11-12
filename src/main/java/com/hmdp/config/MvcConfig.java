package com.hmdp.config;

import com.hmdp.reliability.AccessGuard;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

  @Resource
  private AccessGuard accessGuard;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // Authentication happens in the Security filter chain; this adds rate limits and auditing.
    registry.addInterceptor(accessGuard).addPathPatterns("/**");
  }
}
