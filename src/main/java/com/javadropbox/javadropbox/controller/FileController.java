package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.service.FileService;
import com.javadropbox.javadropbox.service.FileTreeService;
import com.javadropbox.javadropbox.service.StoragePaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Files and folders, addressed by their path relative to the storage root. Items with metadata also
 * have an id, used by the version endpoints in {@link FileVersionController}.
 */
@RestController
@Tag(name = "Files", description = "Browse, upload, download, delete and create files and folders")
public class FileController {

  private final FileService fileService;
  private final FileTreeService fileTreeService;
  private final StoragePaths storagePaths;

  public FileController(
      FileService fileService, FileTreeService fileTreeService, StoragePaths storagePaths) {
    this.fileService = fileService;
    this.fileTreeService = fileTreeService;
    this.storagePaths = storagePaths;
  }

  @GetMapping("/api/storage")
  @Operation(
      summary = "Get storage info",
      description = "Where files are stored and whether that directory is usable.")
  public Map<String, Object> getStorageInfo() {
    Path root = storagePaths.root();
    return Map.of(
        "path", root.toString(),
        "exists", Files.isDirectory(root),
        "readable", Files.isReadable(root),
        "writable", Files.isWritable(root));
  }

  @GetMapping("/api/files")
  @Operation(
      summary = "Get file tree",
      description = "The whole tree of files and folders, folders first, each sorted by name.")
  public List<FileTreeNode> getFileTree() {
    return fileTreeService.tree();
  }

  @GetMapping("/api/files/download")
  @Operation(
      summary = "Download a file or folder",
      description = "The file at the path, or a folder as a zip streamed as it is built.")
  public ResponseEntity<Resource> download(@RequestParam String path, HttpServletResponse response)
      throws IOException {
    return DownloadResponses.send(fileService.download(path), response);
  }

  @PostMapping("/api/files")
  @Operation(
      summary = "Upload files",
      description =
          "Stores one or more files in the folder at path (the root when empty). A file that"
              + " already exists is replaced and its previous content kept as a version.")
  public Map<String, String> upload(
      @RequestParam("files") MultipartFile[] files,
      @RequestParam(value = "path", defaultValue = "") String path)
      throws IOException {
    int stored = fileService.upload(files, path);
    return Map.of("message", "Uploaded " + stored + (stored == 1 ? " file" : " files"));
  }

  @DeleteMapping("/api/files")
  @Operation(
      summary = "Delete a file or folder",
      description = "Deletes the item at path, a folder with everything in it, and its versions.")
  public Map<String, String> delete(@RequestParam String path) throws IOException {
    fileService.delete(path);
    return Map.of("message", "Deleted " + path);
  }

  @PostMapping("/api/folders")
  @Operation(
      summary = "Create a folder",
      description = "Creates a folder called name inside the folder at path.")
  public Map<String, String> createFolder(
      @RequestParam(defaultValue = "") String path, @RequestParam String name) throws IOException {
    fileService.createFolder(path, name);
    return Map.of("message", "Created folder " + name);
  }
}
