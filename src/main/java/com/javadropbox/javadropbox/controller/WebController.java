package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.DownloadableResource;
import com.javadropbox.javadropbox.dto.FileItem;
import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.service.AuthService;
import com.javadropbox.javadropbox.service.FileServingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebController handles HTTP requests for the Java Dropbox application.
 * It provides endpoints for login, dashboard navigation, file management, and directory information.
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
     * @param username the username entered by the user
     * @param password the password entered by the user
     * @param request the HTTP servlet request
     * @param redirectAttributes attributes for redirect scenarios
     * @return redirect to dashboard on successful login, otherwise redirect to login page with error
     */
//    @PostMapping("/login")
//    public String handleLogin(@RequestParam String username,
//                              @RequestParam String password,
//                              HttpServletRequest request,
//                              RedirectAttributes redirectAttributes) {
//
//        if (authService.authenticate(username, password)) {
//            // On successful login, create a session
//            HttpSession session = request.getSession(true);
//            session.setAttribute("user", username);
//
//            redirectAttributes.addAttribute("user", username);
//            return "redirect:/dashboard";
//        } else {
//            redirectAttributes.addAttribute("error", "Invalid username or password");
//            return "redirect:/login.html";
//        }
//    }

    /**
     * Renders the dashboard page with user and directory info.
     * @param principal the user logging in
     * @param model the model to pass attributes to the view
     * @return the dashboard view
     */
    @GetMapping("/dashboard")
    public String dashboard(Principal principal, Model model) {
        model.addAttribute("user", principal.getName());
        model.addAttribute("directory", fileServingService.getServingDirectory());

        return "Dashboard";
    }

    /**
     * Logs out the current user by invalidating their session and redirects to the login page.
     * @param request the HTTP servlet request
     * @return redirect to login
     */
    @GetMapping("/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login";
    }

    /**
     * Prints directory info to the console and redirects to the dashboard.
     * @return redirect to dashboard
     */
    @GetMapping("/directory-info")
    public String directoryInfo() {
        System.out.println(fileServingService.getDirectoryInfo());
        return "redirect:/dashboard";
    }

    /**
     * API endpoint to get directory information.
     * @return a map containing path, existence, readability, and writability of the directory
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
     * @param files the files to upload
     * @param path the target directory path
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

    @GetMapping("/setup")
    public String showSetupPage() {
        return authService.isSetupRequired() ? "Setup" : "redirect:/login";
    }

    @PostMapping("/setup")
    public String processSetup(@RequestParam String username, @RequestParam String password) throws IOException {
        if (authService.isSetupRequired()) {
            authService.completeSetup(username, password);
        }
        return "redirect:/login";
    }
}

