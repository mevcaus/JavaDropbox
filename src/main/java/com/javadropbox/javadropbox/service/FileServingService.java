package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.DownloadableResource;
import com.javadropbox.javadropbox.dto.FileItem;
import com.javadropbox.javadropbox.dto.FileTreeNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FileServingService {

    @Value("${javadropbox.serving.directory:#{systemProperties['user.dir']}}")
    private String servingDirectory;

    public String getServingDirectory() {
        return servingDirectory;
    }

    public File getServingDirectoryFile() {
        return new File(servingDirectory);
    }

    public Path getServingDirectoryPath() {
        return Paths.get(servingDirectory);
    }

    public boolean isValidDirectory() {
        File dir = new File(servingDirectory);
        return dir.exists() && dir.isDirectory();
    }

    public boolean canRead() {
        File dir = new File(servingDirectory);
        return dir.canRead();
    }

    public boolean canWrite() {
        File dir = new File(servingDirectory);
        return dir.canWrite();
    }

    public String getDirectoryInfo() {
        File dir = new File(servingDirectory);
        StringBuilder info = new StringBuilder();

        info.append("Directory: ").append(dir.getAbsolutePath()).append("\n");
        info.append("Exists: ").append(dir.exists()).append("\n");
        info.append("Readable: ").append(dir.canRead()).append("\n");
        info.append("Writable: ").append(dir.canWrite()).append("\n");

        if (dir.exists()) {
            File[] files = dir.listFiles();
            int fileCount = 0;
            int dirCount = 0;

            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        dirCount++;
                    } else {
                        fileCount++;
                    }
                }
            }

            info.append("Files: ").append(fileCount).append("\n");
            info.append("Directories: ").append(dirCount).append("\n");
        }

        return info.toString();
    }

    /**
     * Lists the contents of a given subdirectory within the main serving directory.
     * @param subpath The relative path of the subdirectory. Can be empty for the root.
     * @return A list of FileItem objects representing files and directories.
     */
    public List<FileItem> listFiles(String subpath) {
        Path rootPath = getServingDirectoryPath();
        Path currentPath;

        // Sanitize the subpath to prevent navigation outside the serving directory
        if (subpath == null || subpath.isEmpty() || subpath.equals("/")) {
            currentPath = rootPath;
        } else {
            currentPath = rootPath.resolve(subpath).normalize();
        }

        // Ensure the resolved path is still inside the root serving directory.
        if (!currentPath.startsWith(rootPath)) {
            System.err.println("SECURITY ALERT: Attempted path traversal to: " + currentPath);
            return Collections.emptyList();
        }

        File currentDir = currentPath.toFile();
        if (!currentDir.exists() || !currentDir.isDirectory() || !currentDir.canRead()) {
            return Collections.emptyList();
        }

        File[] files = currentDir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }

        // Sort directories first, then files, all alphabetically
        Arrays.sort(files, Comparator.comparing(File::isDirectory).reversed()
                .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        List<FileItem> fileItems = new ArrayList<>();

        // Add a ".." entry to go up one level, if we are not in the root
        if (!currentPath.equals(rootPath)) {
            fileItems.add(new FileItem("..", true, 0));
        }

        for (File file : files) {
            fileItems.add(
                    new FileItem(file.getName(), file.isDirectory(), file.length())
            );
        }

        return fileItems;
    }

    /**
     * Public method to get the entire directory structure as a tree.
     * @return A list of root-level FileTreeNode objects.
     */
    public List<FileTreeNode> getDirectoryTree() {
        File rootDir = getServingDirectoryFile();
        if (!isValidDirectory() || !canRead()) {
            return Collections.emptyList();
        }
        return buildTreeRecursively(rootDir);
    }

    /**
     * Recursively builds a list of file tree nodes for a given directory,
     * calculating the total size for subdirectories.
     * @param directory The directory to scan.
     * @return A list of nodes representing the contents of the directory.
     */
    private List<FileTreeNode> buildTreeRecursively(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }

        List<FileTreeNode> nodes = new ArrayList<>();
        for (File file : files) {
            FileTreeNode node = new FileTreeNode(file.getName(), file.isDirectory(), file.length());

            if (file.isDirectory()) {
                List<FileTreeNode> children = buildTreeRecursively(file);
                node.setChildren(children);

                long totalSize = children.stream()
                        .mapToLong(FileTreeNode::getSize)
                        .sum();

                node.setSize(totalSize);
            }

            nodes.add(node);
        }

        nodes.sort(Comparator.comparing(FileTreeNode::getIsDirectory).reversed()
                .thenComparing(FileTreeNode::getName, String.CASE_INSENSITIVE_ORDER));

        return nodes;
    }


    /**
     * Prepares a file or a zipped folder for download.
     * @param relativePath The path of the item relative to the serving directory.
     * @return A DownloadableResource containing the data and metadata.
     * @throws IOException if the file is not found or cannot be read.
     */
    public DownloadableResource getResourceForPath(String relativePath) throws IOException {
        Path rootPath = getServingDirectoryPath();
        Path fullPath = rootPath.resolve(relativePath).normalize();

        // --- CRITICAL SECURITY CHECK ---
        if (!fullPath.startsWith(rootPath)) {
            throw new IOException("Path Traversal Attempt Forbidden: " + relativePath);
        }

        File file = fullPath.toFile();
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + relativePath);
        }

        if (file.isDirectory()) {
            // Handle directory zipping
            String zipFilename = file.getName() + ".zip";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zipDirectory(file, file.getName(), zos);
            }

            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            Resource zipResource = new InputStreamResource(bais);

            return new DownloadableResource(zipResource, zipFilename, "application/zip");

        } else {
            // Handle single file download
            Resource resource = new org.springframework.core.io.UrlResource(fullPath.toUri());
            String contentType = Files.probeContentType(fullPath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            return new DownloadableResource(resource, file.getName(), contentType);
        }
    }

    /**
     * Recursively adds files from a directory to a ZipOutputStream.
     * @param folder The folder to zip.
     * @param parentPath The path to prepend to entries in the zip.
     * @param zos The ZipOutputStream to write to.
     * @throws IOException if an error occurs during zipping.
     */
    private void zipDirectory(File folder, String parentPath, ZipOutputStream zos) throws IOException {
        for (File file : folder.listFiles()) {
            if (file.isDirectory()) {
                zipDirectory(file, parentPath + "/" + file.getName(), zos);
                continue;
            }
            ZipEntry zipEntry = new ZipEntry(parentPath + "/" + file.getName());
            zos.putNextEntry(zipEntry);
            Files.copy(file.toPath(), zos);
            zos.closeEntry();
        }
    }
}