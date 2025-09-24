package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.AuthService;
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
            UserDetails user = User.withUsername(storedUsername)
                    .password(storedHash)
                    .roles("USER")
                    .build();
            return user;
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .addFilterBefore(setupFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/setup", "/setup.html", "/login", "/login.html",
                                "/css/**", "/js/**").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/dashboard", true)
                        .failureUrl("/login.html?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login.html?logout")
                        .permitAll()
                )
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }
}