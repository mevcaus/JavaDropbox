package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.FileVersionDto;
import com.javadropbox.javadropbox.model.RestoreMode;
import com.javadropbox.javadropbox.service.FileService;
import com.javadropbox.javadropbox.service.FileVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/files")
@Tag(name = "File Versions", description = "Endpoints for managing file versions")
public class FileVersionController {

  private final FileVersionService fileVersionService;
  private final FileService fileService;

  public FileVersionController(FileVersionService fileVersionService, FileService fileService) {
    this.fileVersionService = fileVersionService;
    this.fileService = fileService;
  }

  @GetMapping("/{fileId}/versions")
  @Operation(
      summary = "Get file versions",
      description = "Returns all versions of a specific file. Requires authentication.")
  public List<FileVersionDto> getFileVersions(@PathVariable Long fileId) {
    return fileVersionService.list(fileId).stream().map(FileVersionDto::fromEntity).toList();
  }

  @PostMapping("/{fileId}/versions/{version}/restore")
  @Operation(
      summary = "Restore file version",
      description =
          "Restores a version over the live file (OVERWRITE, which keeps the live file as a new"
              + " version) or next to it (COPY). Requires authentication.")
  public Map<String, String> restoreVersion(
      @PathVariable Long fileId,
      @PathVariable int version,
      @RequestParam(defaultValue = "OVERWRITE") RestoreMode mode)
      throws IOException {
    fileService.restoreVersion(fileId, version, mode);
    return Map.of("message", "Version " + version + " restored");
  }
}
