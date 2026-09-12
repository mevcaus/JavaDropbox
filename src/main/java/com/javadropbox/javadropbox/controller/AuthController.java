package com.javadropbox.javadropbox.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Auth", description = "Endpoints for authentication check")
public class AuthController {

  @GetMapping("/me")
  @Operation(summary = "Get current authenticated user")
  public Map<String, String> getCurrentUser(Authentication authentication) {
    return Map.of("username", authentication.getName());
  }
}
