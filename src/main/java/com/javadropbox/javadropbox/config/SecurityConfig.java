package com.javadropbox.javadropbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.controller.AuthController;
import com.javadropbox.javadropbox.controller.SpaController;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private static final String X_FRAME_OPTIONS = "X-Frame-Options";

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

  // A disabled account is refused like a wrong password: the sign-in page cannot tell the two
  // apart, so it reveals nothing about which accounts exist.
  @Bean
  public UserDetailsService userDetailsService() {
    return username ->
        userRepository
            .findByUsername(username)
            .map(AccountDetails::new)
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
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, LoginAttemptLimiter limiter, ObjectMapper json) throws Exception {
    // One matcher decides both which requests form login authenticates and which the throttle
    // checks, so the two cannot disagree about a URL such as /logi%6E.
    RequestMatcher loginRequest =
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login");

    LoginThrottleFilter throttle = new LoginThrottleFilter(limiter, loginRequest);
    AccountSessionFilter accountSessions = new AccountSessionFilter(userRepository);

    http.addFilterBefore(setupFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(throttle, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(accountSessions, AnonymousAuthenticationFilter.class)
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
                        // Invitations and password resets, used by someone without a session.
                        "/invite/**",
                        "/reset-password/**",
                        // Only exists in the demo profile, for the sign-in page.
                        "/api/demo",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**")
                    .permitAll()
                    // Health checks come from load balancers and Docker, which have no session.
                    // Anonymous callers only see UP or DOWN (management.endpoint.health.show-
                    // details). Metrics, and managing the accounts, are for admins.
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/**", "/api/admin/**")
                    .hasAuthority(User.ROLE_ADMIN)
                    // A browser opening any other page gets the app shell, which sends it on to
                    // sign-in itself. SpaFallbackFilter answers every request this lets through
                    // with the shell, so nothing else is reached without a session this way.
                    .requestMatchers(SpaFallbackFilter::isNavigation)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        // A JSON API: answer 401 rather than redirecting to a login page.
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .formLogin(
            form ->
                form.loginProcessingUrl("/login")
                    .withObjectPostProcessor(
                        new ObjectPostProcessor<UsernamePasswordAuthenticationFilter>() {
                          @Override
                          public <O extends UsernamePasswordAuthenticationFilter> O postProcess(
                              O filter) {
                            filter.setRequiresAuthenticationRequestMatcher(loginRequest);
                            return filter;
                          }
                        })
                    // Answered like /api/me, so the app knows the role without asking again.
                    .successHandler(
                        (req, res, auth) -> {
                          throttle.recordSuccess(req);
                          accountSessions.signedIn(req, auth);
                          res.setStatus(200);
                          res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                          json.writeValue(res.getWriter(), AuthController.describe(auth));
                        })
                    .failureHandler(
                        (req, res, exc) -> {
                          throttle.recordFailure(req);
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
                    .csrfTokenRequestHandler(csrfTokenRequestHandler()))
        // No response may be framed, as by default, unless it has already said otherwise: a PDF
        // preview allows the app's own pages (see DownloadResponses.preview). The stock writer
        // would overwrite that when the response commits.
        .headers(
            headers ->
                headers
                    .frameOptions(frameOptions -> frameOptions.disable())
                    .addHeaderWriter(
                        (request, response) -> {
                          if (!response.containsHeader(X_FRAME_OPTIONS)) {
                            response.setHeader(X_FRAME_OPTIONS, "DENY");
                          }
                        }));

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
   *
   * <p>A multipart request's token is only taken from the header. Looking for a _csrf parameter
   * makes Tomcat parse the whole body, writing up to the upload limit to disk for a request that is
   * about to be refused. The SPA always sends the header; the parameter is still read from other
   * requests, whose bodies Tomcat reads into memory under a small size limit.
   */
  private static CsrfTokenRequestAttributeHandler csrfTokenRequestHandler() {
    CsrfTokenRequestAttributeHandler handler =
        new CsrfTokenRequestAttributeHandler() {
          @Override
          public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            if (isMultipart(request)) {
              return request.getHeader(csrfToken.getHeaderName());
            }
            return super.resolveCsrfTokenValue(request, csrfToken);
          }
        };
    handler.setCsrfRequestAttributeName(null);
    return handler;
  }

  private static boolean isMultipart(HttpServletRequest request) {
    String contentType = request.getContentType();
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
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
