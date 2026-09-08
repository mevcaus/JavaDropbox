package com.javadropbox.javadropbox.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Tag(name = "Login", description = "Endpoints for login page rendering")
public class LoginController {

  @GetMapping("/")
  @Operation(
      summary = "Index page",
      description =
          "Redirects to dashboard if authenticated, else shows login page. Publicly accessible.")
  public String index() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      return "redirect:/dashboard";
    }
    return "Login";
  }

  @GetMapping("/login")
  @Operation(summary = "Login page", description = "Shows the login page. Publicly accessible.")
  public String loginPage() {
    return "Login";
  }
}
