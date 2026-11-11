package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.Message;
import com.javadropbox.javadropbox.service.SetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
      operationId = "completeSetup",
      summary = "Initial setup",
      description =
          "Creates the admin account. Only available while no account exists, and requires the"
              + " one-time setup code printed in the server log. Repeated wrong codes from one"
              + " client are refused with 429 for a while.")
  public Message processSetup(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String password,
      HttpServletRequest request) {
    setupService.createFirstUser(request.getRemoteAddr(), code, username, password);
    return new Message("Setup successful");
  }
}
