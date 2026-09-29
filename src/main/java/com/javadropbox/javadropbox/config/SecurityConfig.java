package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.controller.SpaController;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final String DEFAULT_ROLE = "ROLE_USER";

  /** The built single-page app: its shell and assets load before anyone has signed in. */
  private static final String[] SPA_ASSETS = {"/index.html", "/assets/**", "/favicon.png"};

  private final SetupFilter setupFilter;
  private final UserRepository userRepository;
  private final List<String> allowedOrigins;

  public SecurityConfig(
      SetupFilter setupFilter,
      UserRepository userRepository,
      @Value("${app.cors.allowed-origins:}") List<String> allowedOrigins) {
    this.setupFilter = setupFilter;
    this.userRepository = userRepository;
    this.allowedOrigins = allowedOrigins.stream().filter(o -> !o.isBlank()).toList();
  }

  @Bean
  public LoginAttemptLimiter loginAttemptLimiter() {
    return new LoginAttemptLimiter(Clock.systemUTC());
  }

  @Bean
  public UserDetailsService userDetailsService() {
    return username ->
        userRepository
            .findByUsername(username)
            .map(
                u ->
                    User.withUsername(u.getUsername())
                        .password(u.getPassword())
                        .authorities(u.getRole() != null ? u.getRole() : DEFAULT_ROLE)
                        .build())
            .orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

  // SetupFilter is a @Component so it can be injected above, which would also make Spring Boot
  // register it as a servlet filter of its own; it belongs only in the security chain.
  @Bean
  public FilterRegistrationBean<SetupFilter> setupFilterRegistration(SetupFilter filter) {
    FilterRegistrationBean<SetupFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginAttemptLimiter limiter)
      throws Exception {
    http.addFilterBefore(setupFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new LoginThrottleFilter(limiter), UsernamePasswordAuthenticationFilter.class)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, SpaController.ROUTES)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, SPA_ASSETS)
                    .permitAll()
                    .requestMatchers(
                        "/setup",
                        "/login",
                        "/error",
                        "/share/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        // A JSON API: answer 401 rather than redirecting to a login page.
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .formLogin(
            form ->
                form.loginProcessingUrl("/login")
                    .successHandler(
                        (req, res, auth) -> {
                          limiter.recordSuccess(req.getRemoteAddr());
                          res.setStatus(200);
                        })
                    .failureHandler(
                        (req, res, exc) -> {
                          limiter.recordFailure(req.getRemoteAddr());
                          res.setStatus(401);
                        })
                    .permitAll())
        .logout(
            logout ->
                logout
                    .logoutUrl("/logout")
                    .logoutSuccessHandler((req, res, auth) -> res.setStatus(200))
                    .permitAll())
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfTokenRequestHandler()));

    return http.build();
  }

  /**
   * Plain (non-BREACH-encoded) request handler so the value the SPA reads from the XSRF-TOKEN
   * cookie is the same value the server expects back in the X-XSRF-TOKEN header.
   *
   * <p>Setting the request attribute name to null opts out of Spring Security's deferred token
   * loading. Deferred loading only resolves the token when something actually reads it, so on a
   * pure-JSON SPA that never renders a server-side form the CookieCsrfTokenRepository would never
   * write the cookie -- leaving the browser with no token to send and every POST, including /login,
   * rejected with 403.
   */
  private static CsrfTokenRequestAttributeHandler csrfTokenRequestHandler() {
    CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
    handler.setCsrfRequestAttributeName(null);
    return handler;
  }

  /** Cross-origin access for {@code app.cors.allowed-origins}; none at all when it is empty. */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    if (allowedOrigins.isEmpty()) {
      return source;
    }
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
