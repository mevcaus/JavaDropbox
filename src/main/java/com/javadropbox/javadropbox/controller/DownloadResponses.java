package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.Download;
import com.javadropbox.javadropbox.dto.Download.FileDownload;
import com.javadropbox.javadropbox.dto.Download.FolderDownload;
import com.javadropbox.javadropbox.dto.Preview;
import com.javadropbox.javadropbox.model.PreviewType;
import com.javadropbox.javadropbox.service.FolderArchive;
import com.javadropbox.javadropbox.service.StoragePaths;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Turns a {@link Download} into an HTTP response, shared by the private and share-link routes, and
 * a {@link Preview} into one for the private preview route.
 */
final class DownloadResponses {

  // The probed type can be text/html or image/svg+xml, served from the app's own origin (on the
  // public share route too). Should a browser ever render one despite the attachment disposition,
  // the sandbox gives it an opaque origin with scripts off, so it cannot read the CSRF cookie or
  // call the API as the user.
  private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
  private static final String SANDBOX = "sandbox";

  // Browsers' built-in PDF viewers refuse to render a sandboxed document, so a PDF preview cannot
  // have the sandbox. It may instead be framed by the app's own pages, which every other response
  // forbids (X-Frame-Options: DENY, see SecurityConfig). Spring Security's nosniff keeps the
  // browser from treating a file named .pdf as anything but a PDF.
  private static final String SAME_ORIGIN_FRAMES = "frame-ancestors 'self'";
  private static final String X_FRAME_OPTIONS = "X-Frame-Options";

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
          .header(CONTENT_SECURITY_POLICY, SANDBOX)
          .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
          .body(new FileSystemResource(StoragePaths.recheck(file.path())));
    }

    FolderDownload folder = (FolderDownload) download;
    response.setContentType("application/zip");
    response.setHeader(CONTENT_SECURITY_POLICY, SANDBOX);
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition);
    String rootName = folder.filename().substring(0, folder.filename().length() - ".zip".length());
    FolderArchive.write(folder.path(), rootName, response.getOutputStream());
    response.flushBuffer();
    return null;
  }

  /**
   * A file shown in the browser instead of saved. Still a resource, so range requests work: the
   * text preview asks for just the start of a large file.
   */
  static ResponseEntity<Resource> preview(Preview preview) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(preview.contentType()))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline()
                    .filename(preview.filename(), StandardCharsets.UTF_8)
                    .build()
                    .toString());
    if (preview.type() == PreviewType.PDF) {
      response.header(CONTENT_SECURITY_POLICY, SAME_ORIGIN_FRAMES);
      response.header(X_FRAME_OPTIONS, "SAMEORIGIN");
    } else {
      // An SVG opened on its own would otherwise run its scripts on the app's origin.
      response.header(CONTENT_SECURITY_POLICY, SANDBOX);
    }
    return response.body(new FileSystemResource(StoragePaths.recheck(preview.path())));
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
