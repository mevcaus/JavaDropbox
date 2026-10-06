package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.service.DemoService;
import com.javadropbox.javadropbox.service.StorageQuota;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the frontend needs to know about the public demo: the shared account, which the sign-in page
 * shows, and its limits. Only exists in the {@code demo} profile; elsewhere the path is a 404.
 * Public, like the sign-in page that reads it.
 */
@RestController
@Profile("demo")
@Tag(name = "Demo", description = "The public demo's account and limits")
public class DemoController {

  /** The demo's account and limits. Sizes are in bytes. */
  public record DemoInfo(
      String username,
      String password,
      long maxUploadBytes,
      Long storageLimitBytes,
      long maxShareMinutes,
      Instant nextReset) {}

  private final DemoService demo;
  private final StorageQuota quota;
  private final DataSize maxUploadSize;
  private final Duration maxShareExpiration;

  public DemoController(
      DemoService demo,
      StorageQuota quota,
      @Value("${spring.servlet.multipart.max-file-size}") DataSize maxUploadSize,
      @Value("${javadropbox.share.max-expiration:7d}") Duration maxShareExpiration) {
    this.demo = demo;
    this.quota = quota;
    this.maxUploadSize = maxUploadSize;
    this.maxShareExpiration = maxShareExpiration;
  }

  @GetMapping("/api/demo")
  @Operation(summary = "Get the demo account and limits")
  public DemoInfo info() {
    return new DemoInfo(
        demo.username(),
        demo.password(),
        maxUploadSize.toBytes(),
        quota.limit().map(DataSize::toBytes).orElse(null),
        maxShareExpiration.toMinutes(),
        demo.nextReset());
  }
}
