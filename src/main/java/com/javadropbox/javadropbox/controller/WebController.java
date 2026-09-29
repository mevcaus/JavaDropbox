package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.service.AuthService;
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

/** File management endpoints and first-run setup. */
@RestController
@Tag(name = "Web", description = "Endpoints for file management, directory info, and setup")
public class WebController {

  private final FileService fileService;
  private final FileTreeService fileTreeService;
  private final StoragePaths storagePaths;
  private final AuthService authService;

  public WebController(
      FileService fileService,
      FileTreeService fileTreeService,
      StoragePaths storagePaths,
      AuthService authService) {
    this.fileService = fileService;
    this.fileTreeService = fileTreeService;
    this.storagePaths = storagePaths;
    this.authService = authService;
  }

  @GetMapping("/api/directory-info")
  @Operation(
      summary = "Get directory info",
      description = "Returns information about the root directory. Requires authentication.")
  public Map<String, Object> getDirectoryInfo() {
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
      description =
          "Returns a hierarchical tree of all files and directories. Requires authentication.")
  public List<FileTreeNode> getFileTree() {
    return fileTreeService.tree();
  }

  @GetMapping("/api/download")
  @Operation(
      summary = "Download a file or folder",
      description =
          "Downloads the file at the path, or a folder as a zip. Requires authentication.")
  public ResponseEntity<Resource> downloadFileOrFolder(
      @RequestParam String path, HttpServletResponse response) throws IOException {
    return DownloadResponses.send(fileService.download(path), response);
  }

  @PostMapping("/api/upload")
  @Operation(
      summary = "Upload files",
      description =
          "Uploads one or more files to the specified directory path. Requires authentication.")
  public Map<String, String> uploadFiles(
      @RequestParam("files") MultipartFile[] files,
      @RequestParam(value = "path", defaultValue = "") String path)
      throws IOException {
    fileService.upload(files, path);
    return Map.of("message", "Files uploaded successfully!");
  }

  @DeleteMapping("/api/delete")
  @Operation(
      summary = "Delete an item",
      description = "Deletes the file or folder at the specified path. Requires authentication.")
  public Map<String, String> deleteItem(@RequestParam String path) throws IOException {
    fileService.delete(path);
    return Map.of("message", "Item deleted successfully: " + path);
  }

  @PostMapping("/api/create-directory")
  @Operation(
      summary = "Create directory",
      description = "Creates a new directory at the specified path. Requires authentication.")
  public Map<String, String> createDirectory(@RequestParam String path, @RequestParam String name)
      throws IOException {
    fileService.createFolder(path, name);
    return Map.of("message", "Directory created successfully: " + name);
  }

  @PostMapping("/setup")
  @Operation(
      summary = "Initial setup",
      description =
          "Creates the initial admin user. Allowed without authentication if setup is required.")
  public ResponseEntity<?> processSetup(
      @RequestParam String username, @RequestParam String password) {
    if (username == null || username.trim().isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "Username required"));
    }
    if (password == null || password.trim().isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "Password required"));
    }
    if (authService.isSetupRequired()) {
      authService.setupUser(username, password);
      return ResponseEntity.ok(Map.of("message", "Setup successful"));
    }
    return ResponseEntity.badRequest().body(Map.of("error", "Setup already completed"));
  }
}
