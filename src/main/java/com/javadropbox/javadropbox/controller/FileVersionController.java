package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.FileVersionDto;
import com.javadropbox.javadropbox.model.RestoreMode;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import com.javadropbox.javadropbox.service.FileServingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files")
@Tag(name = "File Versions", description = "Endpoints for managing file versions")
public class FileVersionController {

  private final FileMetadataRepository fileMetadataRepository;
  private final FileVersionRepository fileVersionRepository;
  private final FileServingService fileServingService;

  public FileVersionController(
      FileMetadataRepository fileMetadataRepository,
      FileVersionRepository fileVersionRepository,
      FileServingService fileServingService) {
    this.fileMetadataRepository = fileMetadataRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.fileServingService = fileServingService;
  }

  @GetMapping("/{fileId}/versions")
  @Operation(
      summary = "Get file versions",
      description = "Returns all versions of a specific file. Requires authentication.")
  public ResponseEntity<?> getFileVersions(@PathVariable Long fileId) {
    return fileMetadataRepository
        .findById(fileId)
        .map(
            metadata -> {
              List<FileVersionDto> versions =
                  fileVersionRepository.findByFileMetadataOrderByVersionDesc(metadata).stream()
                      .map(FileVersionDto::fromEntity)
                      .collect(Collectors.toList());
              return ResponseEntity.ok(versions);
            })
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/{fileId}/versions/{version}/restore")
  @Operation(
      summary = "Restore file version",
      description = "Restores a specific version of a file. Requires authentication.")
  public ResponseEntity<?> restoreVersion(
      @PathVariable Long fileId,
      @PathVariable Integer version,
      @RequestParam(defaultValue = "OVERWRITE") RestoreMode mode) {
    try {
      fileServingService.restoreVersion(fileId, version, mode);
      return ResponseEntity.ok(Map.of("message", "Version restored successfully via " + mode));
    } catch (IOException e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("message", "Restore failed: " + e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("message", "Restore failed: " + e.getMessage()));
    }
  }
}
