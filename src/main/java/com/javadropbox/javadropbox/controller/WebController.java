package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.DownloadableResource;
import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.service.AuthService;
import com.javadropbox.javadropbox.service.FileServingService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebController handles HTTP requests for the Java Dropbox application.
 * It provides endpoints for login, dashboard navigation, file management, and
 * directory information.
 */
@Controller
public class WebController {

    @Autowired
    private FileServingService fileServingService;
    @Autowired
    private AuthService authService;

    /**
     * Handles login form submission.
     *
     * @param username           the username entered by the user
     * @param password           the password entered by the user
     * @param request            the HTTP servlet request
     * @param redirectAttributes attributes for redirect scenarios
     * @return redirect to dashboard on successful login, otherwise redirect to
     *         login page with error
     */
    // @PostMapping("/login")
    // public String handleLogin(@RequestParam String username,
    // @RequestParam String password,
    // HttpServletRequest request,
    // RedirectAttributes redirectAttributes) {
    //
    // if (authService.authenticate(username, password)) {
    // // On successful login, create a session
    // HttpSession session = request.getSession(true);
    // session.setAttribute("user", username);
    //
    // redirectAttributes.addAttribute("user", username);
    // return "redirect:/dashboard";
    // } else {
    // redirectAttributes.addAttribute("error", "Invalid username or password");
    // return "redirect:/login.html";
    // }
    // }

    /**
     * Renders the dashboard page with user and directory info.
     * 
     * @param principal the user logging in
     * @param model     the model to pass attributes to the view
     * @return the dashboard view
     */
    /**
     * API endpoint to get directory information.
     * 
     * @return a map containing path, existence, readability, and writability of the
     *         directory
     */
    @GetMapping("/api/directory-info")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getDirectoryInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("path", fileServingService.getServingDirectory());
        info.put("exists", fileServingService.isValidDirectory());
        info.put("readable", fileServingService.canRead());
        info.put("writable", fileServingService.canWrite());

        return ResponseEntity.ok(info);
    }

    /**
     * API endpoint to get the list of files and directories.
     * 
     * @return a JSON array of file tree nodes
     */
    @GetMapping("/api/files")
    @ResponseBody
    public ResponseEntity<List<FileTreeNode>> getFileTree(HttpSession session) {
        List<FileTreeNode> tree = fileServingService.getDirectoryTree();
        return ResponseEntity.ok(tree);
    }

    /**
     * API endpoint to download a file or folder.
     * 
     * @param path the path to the file or folder
     * @return the resource to download, or 404 if not found
     */
    @GetMapping("/api/download")
    public ResponseEntity<Resource> downloadFileOrFolder(@RequestParam String path, HttpSession session) {
        try {
            DownloadableResource downloadable = fileServingService.getResourceForPath(path);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(downloadable.contentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadable.filename() + "\"")
                    .body(downloadable.resource());

        } catch (IOException e) {
            // E.g., file not found or security violation
            System.err.println("Error processing download request: " + e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * API endpoint to upload files.
     * 
     * @param files the files to upload
     * @param path  the target directory path
     * @return a message indicating success or failure
     */
    @PostMapping("/api/upload")
    public ResponseEntity<Map<String, String>> uploadFiles(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "path", defaultValue = "") String path,
            HttpSession session) {

        try {
            fileServingService.saveUploadedFiles(files, path);
            return ResponseEntity.ok(Map.of("message", "Files uploaded successfully!"));
        } catch (IOException e) {
            System.err.println("File upload error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error uploading files: " + e.getMessage()));
        }
    }

    /**
     * API endpoint to delete a file or folder.
     * 
     * @param path the path to the item to delete
     * @return a message indicating success or failure
     */
    @DeleteMapping("/api/delete")
    public ResponseEntity<Map<String, String>> deleteItem(@RequestParam String path, HttpSession session) {
        try {
            fileServingService.deleteItem(path);
            return ResponseEntity.ok(Map.of("message", "Item deleted successfully: " + path));
        } catch (IOException e) {
            System.err.println("Error deleting item: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Could not delete item: " + e.getMessage()));
        }
    }

    @PostMapping("/setup")
    public ResponseEntity<?> processSetup(@RequestParam String username, @RequestParam String password) {
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

    @PostMapping("/api/create-directory")
    public ResponseEntity<Map<String, String>> createDirectory(
            @RequestParam String path,
            @RequestParam String name,
            HttpSession session) {
        try {
            fileServingService.createDirectory(path, name);
            return ResponseEntity.ok(Map.of("message", "Directory created successfully: " + name));
        } catch (IOException e) {
            System.err.println("Error creating directory: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
    }
}
