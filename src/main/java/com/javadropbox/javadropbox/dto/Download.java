package com.javadropbox.javadropbox.dto;

import java.io.IOException;
import java.io.OutputStream;
import org.springframework.core.io.Resource;

/** Something a client can download: a single file, or a folder sent as a zip. */
public sealed interface Download {

  /** The name the client should save it under. */
  String filename();

  /** A file, read only once the response is written. */
  record FileDownload(Resource content, String filename, String contentType) implements Download {}

  /** A folder, zipped as it is written to the response. */
  record FolderDownload(String filename, Zip zip) implements Download {}

  /** Writes a folder as a zip. */
  @FunctionalInterface
  interface Zip {
    void writeTo(OutputStream out) throws IOException;
  }
}
