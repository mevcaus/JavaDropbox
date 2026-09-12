package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.AuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final SetupFilter setupFilter;
  private final AuthService authService;

  public SecurityConfig(SetupFilter setupFilter, AuthService authService) {
    this.setupFilter = setupFilter;
    this.authService = authService;
  }

  @Bean
  public UserDetailsService userDetailsService() {
    return username -> {
      if (authService.isSetupRequired()) {
        throw new UsernameNotFoundException("Setup not completed");
      }
      return authService
          .getMainUser()
          .filter(u -> u.getUsername().equals(username))
          .map(
              u ->
                  User.withUsername(u.getUsername())
                      .password(u.getPassword())
                      .roles("USER")
                      .build())
          .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    };
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.addFilterBefore(setupFilter, UsernamePasswordAuthenticationFilter.class)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/setup",
                        "/login",
                        "/error",
                        "/share/**",
                        // Allow access to Swagger UI and OpenAPI docs without authentication in dev
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                    new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                        org.springframework.http.HttpStatus.UNAUTHORIZED)))
        .formLogin(
            form ->
                form.loginProcessingUrl("/login")
                    .successHandler((req, res, auth) -> res.setStatus(200))
                    .failureHandler((req, res, exc) -> res.setStatus(401))
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

  @Bean
  public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
    org.springframework.web.cors.CorsConfiguration configuration =
        new org.springframework.web.cors.CorsConfiguration();
    configuration.setAllowedOrigins(
        java.util.List.of("http://localhost:5173", "http://localhost:5174"));
    configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(java.util.List.of("*"));
    configuration.setAllowCredentials(true);
    org.springframework.web.cors.UrlBasedCorsConfigurationSource source =
        new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
