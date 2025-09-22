package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.DownloadableResource;
import com.javadropbox.javadropbox.dto.FileItem;
import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.service.FileServingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@org.springframework.stereotype.Controller
public class WebController {

    @Autowired
    private FileServingService fileServingService;

    @GetMapping("/")
    public String index() {
        return "redirect:/login.html";
    }

    @GetMapping("/login")
    public String login() {
        return "redirect:/login.html";
    }

    @PostMapping("/login")
    public String handleLogin(@RequestParam String username,
                              @RequestParam String password,
                              RedirectAttributes redirectAttributes) {

        if (username != null && !username.trim().isEmpty() &&
                password != null && !password.trim().isEmpty()) {

            redirectAttributes.addAttribute("user", username);
            return "redirect:/dashboard";

        } else {
            redirectAttributes.addAttribute("error", "Please enter both username and password");
            return "redirect:/login.html";
        }
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam String user, RedirectAttributes redirectAttributes) {
        redirectAttributes.addAttribute("user", user);

        redirectAttributes.addAttribute("directory", fileServingService.getServingDirectory());

        return "redirect:/dashboard.html";
    }

    @GetMapping("/logout")
    public String logout() {
        return "redirect:/login.html";
    }

    @GetMapping("/directory-info")
    public String directoryInfo() {
        System.out.println(fileServingService.getDirectoryInfo());
        return "redirect:/dashboard";
    }

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
     * @return A JSON array of file items.
     */
    @GetMapping("/api/files")
    @ResponseBody
    public ResponseEntity<List<FileTreeNode>> getFileTree() {
        List<FileTreeNode> tree = fileServingService.getDirectoryTree();
        return ResponseEntity.ok(tree);
    }


    @GetMapping("/api/download")
    public ResponseEntity<Resource> downloadFileOrFolder(@RequestParam String path) {
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
}