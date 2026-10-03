package com.javadropbox.javadropbox.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * How a file can be shown in the browser, decided by its extension. The server owns this list, and
 * the tree carries the answer to the UI, so the two can never disagree about which files open.
 *
 * <p>Extensions rather than {@link java.nio.file.Files#probeContentType}: the probe depends on the
 * host's MIME database, which a slim container image may not have, and the answer here must not
 * change with where the server runs.
 */
public enum PreviewType {
  IMAGE,
  PDF,
  // Always served as text/plain, so markup and scripts show as source and are never rendered.
  TEXT;

  private static final String TEXT_PLAIN = "text/plain;charset=UTF-8";

  private static final Map<String, PreviewType> TYPES = new HashMap<>();
  private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

  // Plain-text files conventionally named without an extension.
  private static final Set<String> TEXT_NAMES =
      Set.of("readme", "license", "dockerfile", "makefile");

  static {
    image("png", "image/png");
    image("jpg", "image/jpeg");
    image("jpeg", "image/jpeg");
    image("gif", "image/gif");
    image("webp", "image/webp");
    image("avif", "image/avif");
    image("bmp", "image/bmp");
    image("svg", "image/svg+xml");

    TYPES.put("pdf", PDF);
    CONTENT_TYPES.put("pdf", "application/pdf");

    for (String extension :
        new String[] {
          "txt",
          "md",
          "markdown",
          "csv",
          "tsv",
          "log",
          "json",
          "xml",
          "yml",
          "yaml",
          "toml",
          "ini",
          "cfg",
          "conf",
          "properties",
          "html",
          "htm",
          "css",
          "scss",
          "js",
          "jsx",
          "mjs",
          "cjs",
          "ts",
          "tsx",
          "java",
          "kt",
          "gradle",
          "py",
          "rb",
          "go",
          "rs",
          "c",
          "h",
          "cpp",
          "hpp",
          "cs",
          "php",
          "swift",
          "sh",
          "bash",
          "zsh",
          "ps1",
          "bat",
          "sql"
        }) {
      TYPES.put(extension, TEXT);
    }
  }

  private static void image(String extension, String contentType) {
    TYPES.put(extension, IMAGE);
    CONTENT_TYPES.put(extension, contentType);
  }

  /** The preview for a file name, if there is one. */
  public static Optional<PreviewType> of(String filename) {
    String lower = filename.toLowerCase(Locale.ROOT);
    if (TEXT_NAMES.contains(lower)) {
      return Optional.of(TEXT);
    }
    return Optional.ofNullable(TYPES.get(extension(lower)));
  }

  /** The Content-Type a previewable file is served with. */
  public static String contentType(String filename) {
    PreviewType type =
        of(filename)
            .orElseThrow(() -> new IllegalArgumentException("Not previewable: " + filename));
    return type == TEXT
        ? TEXT_PLAIN
        : CONTENT_TYPES.get(extension(filename.toLowerCase(Locale.ROOT)));
  }

  // Everything after the last dot, so "archive.tar.gz" is "gz". Empty without a dot, which no
  // entry matches.
  private static String extension(String lowerCaseName) {
    int dot = lowerCaseName.lastIndexOf('.');
    return dot >= 0 ? lowerCaseName.substring(dot + 1) : "";
  }

  @JsonValue
  public String jsonValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
