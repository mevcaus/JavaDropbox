package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.HistoryPage;
import com.javadropbox.javadropbox.service.AuthService;
import com.javadropbox.javadropbox.service.FileHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "History", description = "Endpoints for viewing file history")
public class HistoryController {

  private final FileHistoryService fileHistoryService;
  private final AuthService authService;

  public HistoryController(FileHistoryService fileHistoryService, AuthService authService) {
    this.fileHistoryService = fileHistoryService;
    this.authService = authService;
  }

  @GetMapping("/api/history")
  @Operation(
      summary = "Get file history",
      description =
          "Returns one page of the signed-in user's file operation history, newest first. Pages"
              + " start at 0; size is capped at "
              + FileHistoryService.MAX_PAGE_SIZE
              + ".")
  public HistoryPage getHistory(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return fileHistoryService.page(authService.requireCurrentUser(), page, size);
  }
}
