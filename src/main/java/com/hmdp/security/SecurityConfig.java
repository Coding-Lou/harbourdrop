package com.hmdp.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless API security: JWT bearer tokens (OAuth2 resource server) for users, a static credential
 * for Prometheus, and role-based rules for administration.
 *
 * <p>Route rules are evaluated on the normalized request path; Spring Security's firewall also
 * rejects encoded slashes, {@code ;} parameters and {@code ..} segments before any rule runs.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
  private static final ObjectMapper JSON =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

  @Bean
  SecretKey jwtSigningKey(@Value("${hmdp.security.jwt-secret}") String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) {
      throw new IllegalStateException("hmdp.security.jwt-secret must be at least 32 bytes");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder jwtDecoder(SecretKey key, TokenService tokens) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> notRevoked =
        jwt ->
            tokens.isRevoked(jwt.getId())
                ? OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Token has been revoked", null))
                : OAuth2TokenValidatorResult.success();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(TokenService.ISSUER), notRevoked));
    return decoder;
  }

  @Bean
  SecurityFilterChain api(
      HttpSecurity http,
      @Value("${hmdp.monitor-token:}") String monitorToken,
      @Value("${citylens.catalog-admin-ids:}") String adminIds)
      throws Exception {
    Set<String> admins =
        Arrays.stream(adminIds.split(","))
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    MonitorTokenFilter monitor = new MonitorTokenFilter(monitorToken);
    DefaultBearerTokenResolver jwtResolver = new DefaultBearerTokenResolver();
    // The monitor credential is not a JWT; keep the resource server from trying to decode it.
    BearerTokenResolver resolver =
        request ->
            monitor.isMonitorCredential(request.getHeader("Authorization"))
                ? null
                : jwtResolver.resolve(request);
    AuthenticationEntryPoint unauthenticated =
        (request, response, e) -> write(response, 401, "请先登录");
    AccessDeniedHandler denied = (request, response, e) -> write(response, 403, "当前账号没有管理权限");

    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/error")
                    .permitAll()
                    // Administration and monitoring.
                    .requestMatchers("/ops/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, MonitorTokenFilter.METRICS_PATH)
                    .hasAnyRole("ADMIN", "MONITOR")
                    .requestMatchers(HttpMethod.GET, "/actuator/health")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    .requestMatchers(HttpMethod.POST, "/shop", "/shop/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/shop", "/shop/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/shop", "/shop/**")
                    .hasRole("ADMIN")
                    // Public catalogue reads.
                    .requestMatchers(
                        HttpMethod.GET,
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/blog/hot",
                        "/blog/of/shop",
                        "/blog/search")
                    .permitAll()
                    .requestMatchers(HttpMethod.HEAD, "/shop/**", "/shop-type/**", "/voucher/**")
                    .permitAll()
                    // Login flow.
                    .requestMatchers(HttpMethod.POST, "/user/code", "/user/login", "/user/refresh")
                    .permitAll()
                    // Public experience sampling (rate limited in AccessGuard).
                    .requestMatchers(
                        HttpMethod.GET,
                        "/experience/capabilities",
                        "/experience/cache",
                        "/experience/visits")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/experience/visits")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .bearerTokenResolver(resolver)
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(denied)
                    .jwt(
                        jwt ->
                            jwt.jwtAuthenticationConverter(
                                token -> new JwtAuthenticationToken(token, roles(token, admins)))))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied))
        .addFilterBefore(monitor, BearerTokenAuthenticationFilter.class)
        .addFilterAfter(new UserContextFilter(), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  /**
   * The admin list is read at startup and the role is never stored in tokens, so after a restart
   * even already-issued tokens are judged against the new list.
   */
  private static List<GrantedAuthority> roles(Jwt token, Set<String> admins) {
    List<GrantedAuthority> roles = new ArrayList<>();
    roles.add(new SimpleGrantedAuthority("ROLE_USER"));
    if (admins.contains(token.getSubject())) {
      roles.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    return roles;
  }

  private static void write(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(JSON.writeValueAsString(Result.fail(message)));
  }
}
