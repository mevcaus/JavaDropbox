package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.Download;
import com.javadropbox.javadropbox.dto.Download.FileDownload;
import com.javadropbox.javadropbox.dto.Download.FolderDownload;
import com.javadropbox.javadropbox.service.FolderArchive;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Turns a {@link Download} into an HTTP response, shared by the private and share-link routes. */
final class DownloadResponses {

  private DownloadResponses() {}

  /**
   * A file is returned as a resource, which keeps HTTP range requests (resumable downloads)
   * working. A folder is zipped straight into the response and {@code null} is returned, which
   * tells Spring the response has already been written.
   */
  static ResponseEntity<Resource> send(Download download, HttpServletResponse response)
      throws IOException {
    String disposition = attachment(download.filename());

    if (download instanceof FileDownload file) {
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType(file.contentType()))
          .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
          .body(new FileSystemResource(file.path()));
    }

    FolderDownload folder = (FolderDownload) download;
    response.setContentType("application/zip");
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition);
    String rootName = folder.filename().substring(0, folder.filename().length() - ".zip".length());
    FolderArchive.write(folder.path(), rootName, response.getOutputStream());
    response.flushBuffer();
    return null;
  }

  // Built rather than concatenated: a name with a quote, semicolon or non-ASCII characters would
  // otherwise break the header or smuggle parameters into it.
  static String attachment(String filename) {
    return ContentDisposition.attachment()
        .filename(filename, StandardCharsets.UTF_8)
        .build()
        .toString();
  }
}
