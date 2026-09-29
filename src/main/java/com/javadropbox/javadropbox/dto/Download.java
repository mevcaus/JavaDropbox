package com.javadropbox.javadropbox.dto;

import java.nio.file.Path;

/** Something a client can download: a single file, or a folder sent as a zip. */
public sealed interface Download {

  /** The name the client should save it under. */
  String filename();

  record FileDownload(Path path, String filename, String contentType) implements Download {}

  record FolderDownload(Path path, String filename) implements Download {}
}
