package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class SetupFilter extends OncePerRequestFilter {

    private final AuthService authService;

    public SetupFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestURI = request.getRequestURI();

        if (authService.isSetupRequired()) {
            if (requestURI.equals("/setup.html") || requestURI.equals("/setup") || requestURI.startsWith("/css/")) {
                filterChain.doFilter(request, response);
            } else {
                response.sendRedirect("/setup.html");
            }
        } else {
            if (requestURI.equals("/setup.html") || requestURI.equals("/setup")) {
                response.sendRedirect("/login.html");
            } else {
                filterChain.doFilter(request, response);
            }
        }
    }
}