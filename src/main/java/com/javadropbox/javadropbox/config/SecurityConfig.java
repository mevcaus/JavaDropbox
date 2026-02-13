package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.AuthService;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
                        return authService.getMainUser()
                                        .filter(u -> u.getUsername().equals(username))
                                        .map(u -> User.withUsername(u.getUsername())
                                                        .password(u.getPassword())
                                                        .roles("USER")
                                                        .build())
                                        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
                };
        }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http)
                        throws Exception {
                http
                                .addFilterBefore(setupFilter, UsernamePasswordAuthenticationFilter.class)
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers(
                                                                "/setup",
                                                                "/login",
                                                                "/setup",
                                                                "/login",
                                                                "/error")
                                                .permitAll()
                                                .anyRequest()
                                                .authenticated())
                                .exceptionHandling(ex -> ex
                                                .authenticationEntryPoint(
                                                                new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                                                                                org.springframework.http.HttpStatus.UNAUTHORIZED)))
                                .formLogin(form -> form
                                                .loginProcessingUrl("/login")
                                                .successHandler((req, res, auth) -> res.setStatus(200))
                                                .failureHandler((req, res, exc) -> res.setStatus(401))
                                                .permitAll())
                                .rememberMe(rememberMe -> rememberMe
                                                .key(UUID.randomUUID().toString())
                                                .tokenValiditySeconds(60))
                                .logout(logout -> logout
                                                .logoutUrl("/logout")
                                                .logoutSuccessUrl("/login?logout")
                                                .permitAll())
                                // -- DO NOT DO THIS IN PRODUCTION --
                                .csrf(AbstractHttpConfigurer::disable);

                return http.build();
        }

        @Bean
        public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
                org.springframework.web.cors.CorsConfiguration configuration = new org.springframework.web.cors.CorsConfiguration();
                configuration.setAllowedOrigins(java.util.List.of("http://localhost:5173", "http://localhost:5174"));
                configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                configuration.setAllowedHeaders(java.util.List.of("*"));
                configuration.setAllowCredentials(true);
                org.springframework.web.cors.UrlBasedCorsConfigurationSource source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }
}