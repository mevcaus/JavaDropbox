package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.UploadLimits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The limits on one upload request, read from the same settings Tomcat and Spring enforce them with
 * ({@code spring.servlet.multipart.max-file-size}, {@code .max-request-size} and {@code
 * server.tomcat.max-part-count}), so the app's batches follow whatever an install sets.
 */
@RestController
@Tag(name = "Files", description = "Browse, upload, download, delete and create files and folders")
public class UploadLimitsController {

  private final UploadLimits limits;

  public UploadLimitsController(MultipartProperties multipart, ServerProperties server) {
    int parts = server.getTomcat().getMaxPartCount();
    this.limits =
        new UploadLimits(
            bytes(multipart.getMaxFileSize()),
            bytes(multipart.getMaxRequestSize()),
            // A negative count is no limit. The path takes one part, which leaves at least one
            // file.
            parts < 0 ? null : Math.max(1, parts - 1));
  }

  // A negative size is no limit, as Spring reads it.
  private static @Nullable Long bytes(DataSize size) {
    return size.isNegative() ? null : size.toBytes();
  }

  @GetMapping("/api/files/upload-limits")
  @Operation(
      operationId = "getUploadLimits",
      summary = "Get upload limits",
      description =
          "The largest file, the largest request and the most files one upload request may carry,"
              + " each null for no limit. Sizes are in bytes.")
  public UploadLimits uploadLimits() {
    return limits;
  }
}
