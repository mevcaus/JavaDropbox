package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.service.SetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Setup", description = "First-run creation of the admin account")
public class SetupController {

  private final SetupService setupService;

  public SetupController(SetupService setupService) {
    this.setupService = setupService;
  }

  @PostMapping("/setup")
  @Operation(
      summary = "Initial setup",
      description =
          "Creates the admin account. Only available while no account exists, and requires the"
              + " one-time setup code printed in the server log.")
  public Map<String, String> processSetup(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String password) {
    setupService.createFirstUser(code, username, password);
    return Map.of("message", "Setup successful");
  }
}
