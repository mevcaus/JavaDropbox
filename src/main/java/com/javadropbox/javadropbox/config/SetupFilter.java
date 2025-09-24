package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class SetupFilter extends OncePerRequestFilter {

    private final AuthService authService;

    // -- setup filter is ignored in test suite --
    @Value("${app.setup.filter.enabled:true}")
    private boolean filterEnabled;

    public SetupFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!filterEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestURI = request.getRequestURI();

        if (authService.isSetupRequired()) {
            if (requestURI.equals("/setup") || requestURI.startsWith("/css/") ||
                    requestURI.startsWith("/js/") || requestURI.startsWith("/images/")) {
                filterChain.doFilter(request, response);
            } else {
                response.sendRedirect("/setup");
            }
        } else {
            if (requestURI.equals("/setup")) {
                response.sendRedirect("/login");
            } else {
                filterChain.doFilter(request, response);
            }
        }
    }
}