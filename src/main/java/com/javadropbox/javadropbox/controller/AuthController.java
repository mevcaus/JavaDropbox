package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Auth", description = "Endpoints for authentication check")
public class AuthController {

  @GetMapping("/me")
  @Operation(
      summary = "Get current authenticated user",
      description = "The signed-in user's username, and role: ADMIN or USER.")
  public Map<String, String> getCurrentUser(Authentication authentication) {
    return describe(authentication);
  }

  /** Who is signed in, as /api/me and a successful sign-in answer it. */
  public static Map<String, String> describe(Authentication authentication) {
    boolean admin =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(User.ROLE_ADMIN::equals);
    return Map.of("username", authentication.getName(), "role", admin ? "ADMIN" : "USER");
  }
}
