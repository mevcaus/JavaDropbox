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
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        return username -> {
            if (authService.isSetupRequired()) {
                throw new UsernameNotFoundException("Setup not completed");
            }
            String storedUsername = authService.getStoredUsername();
            String storedHash = authService.getStoredPasswordHash();
            if (storedUsername == null || storedHash == null) {
                throw new UsernameNotFoundException("No stored user");
            }
            if (!storedUsername.equals(username)) {
                throw new UsernameNotFoundException("User not found");
            }
            return User.withUsername(storedUsername)
                    .password(storedHash)
                    .roles("USER")
                    .build();
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {
        http
                .addFilterBefore(setupFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth ->
                        auth
                                .requestMatchers(
                                        "/setup",
                                        "/login",
                                        "/css/**",
                                        "/js/**",
                                        "/images/**",
                                        "/JavaDropbox_favicon.png"
                                )
                                .permitAll()
                                .anyRequest()
                                .authenticated()
                )
                .formLogin(form ->
                        form
                                .loginPage("/login")
                                .loginProcessingUrl("/login")
                                .defaultSuccessUrl("/dashboard", true)
                                .failureUrl("/login?error")
                                .permitAll()
                )
                .rememberMe(rememberMe ->
                        rememberMe
                                .key(UUID.randomUUID().toString())
                                .tokenValiditySeconds(60)
//                                .tokenValiditySeconds(86400 * 14) // 14 day cookie
                )
                .logout(logout ->
                        logout
                                .logoutUrl("/logout")
                                .logoutSuccessUrl("/login?logout")
                                .permitAll()
                )
                // -- DO NOT DO THIS IN PRODUCTION --
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }
}