package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.DownloadableResource;
import com.javadropbox.javadropbox.dto.FileItem;
import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.model.FileHistory;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.FileVersion;
import com.javadropbox.javadropbox.model.RestoreMode;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileHistoryRepository;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.FileVersionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FileServingService {

    private static final int MAX_FILE_VERSIONS = 10;
    private static final String VERSIONS_DIR_NAME = ".versions";
    private static final String SECURITY_ALERT_MESSAGE = "SECURITY ALERT: Attempted path traversal to: ";
    private static final String PATH_TRAVERSAL_ERROR = "Path Traversal Attempt Forbidden: ";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm a");
    private static final String FALLBACK_OWNER = "system";

    @Value("${javadropbox.serving.directory:#{systemProperties['user.dir']}}")
    private String servingDirectory;

    private final FileMetadataRepository fileMetadataRepository;
    private final FileHistoryRepository fileHistoryRepository;
    private final FileVersionRepository fileVersionRepository;
    private final AuthService authService;

    public FileServingService(FileMetadataRepository fileMetadataRepository,
            FileHistoryRepository fileHistoryRepository,
            FileVersionRepository fileVersionRepository,
            AuthService authService) {
        this.fileMetadataRepository = fileMetadataRepository;
        this.fileHistoryRepository = fileHistoryRepository;
        this.fileVersionRepository = fileVersionRepository;
        this.authService = authService;
    }

    private void validatePathSecurity(Path fullPath, String relativePath) throws IOException {
        Path rootPath = getServingDirectoryPath();
        if (!fullPath.startsWith(rootPath)) {
            System.err.println(SECURITY_ALERT_MESSAGE + fullPath);
            throw new IOException(PATH_TRAVERSAL_ERROR + relativePath);
        }
    }

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

    public List<FileItem> listFiles(String subpath) {
        // Keeping FS as source of truth for listing for now
        Path rootPath = getServingDirectoryPath();
        Path currentPath;

        if (subpath == null || subpath.isEmpty() || subpath.equals("/")) {
            currentPath = rootPath;
        } else {
            currentPath = rootPath.resolve(subpath).normalize();
        }

        try {
            validatePathSecurity(currentPath, subpath);
        } catch (IOException e) {
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

        Arrays.sort(files, Comparator.comparing(File::isDirectory).reversed()
                .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        List<FileItem> fileItems = new ArrayList<>();

        if (!currentPath.equals(rootPath)) {
            fileItems.add(new FileItem("..", true, 0));
        }

        for (File file : files) {
            if (file.getName().startsWith(".")) {
                continue;
            }
            fileItems.add(
                    new FileItem(file.getName(), file.isDirectory(), file.length()));
        }

        return fileItems;
    }

    public List<FileTreeNode> getDirectoryTree() {
        File rootDir = getServingDirectoryFile();
        if (!isValidDirectory() || !canRead()) {
            return Collections.emptyList();
        }
        return buildTreeRecursively(rootDir, "");
    }

    private List<FileTreeNode> buildTreeRecursively(File directory, String relativePath) {
        File[] files = directory.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }

        List<FileTreeNode> nodes = new ArrayList<>();

        for (File file : files) {
            if (file.getName().startsWith(".")) {
                continue;
            }
            String currentPath = relativePath.isEmpty() ? file.getName() : relativePath + "/" + file.getName();
            FileTreeNode node = new FileTreeNode(file.getName(), file.isDirectory(), file.length(), currentPath);

            populateNodeMetadata(node, currentPath, file);

            if (file.isDirectory()) {
                List<FileTreeNode> children = buildTreeRecursively(file, currentPath);
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

    private void populateNodeMetadata(FileTreeNode node, String currentPath, File file) {
        Optional<FileMetadata> metadataOpt = fileMetadataRepository.findByPath(currentPath);

        if (metadataOpt.isPresent()) {
            FileMetadata meta = metadataOpt.get();
            node.setId(meta.getId());
            if (meta.getCreatedAt() != null) {
                node.setCreatedDate(meta.getCreatedAt().format(DATE_FORMATTER));
            }
            if (meta.getUpdatedAt() != null) {
                node.setLastModified(meta.getUpdatedAt().format(DATE_FORMATTER));
            }
            if (meta.getOwner() != null) {
                node.setOwnerName(meta.getOwner().getUsername());
            }
        } else {
            populateNodeFromFileSystem(node, file);
        }
    }

    private void populateNodeFromFileSystem(FileTreeNode node, File file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            LocalDateTime created = LocalDateTime.ofInstant(
                    attrs.creationTime().toInstant(),
                    ZoneId.systemDefault());
            LocalDateTime modified = LocalDateTime.ofInstant(
                    attrs.lastModifiedTime().toInstant(),
                    ZoneId.systemDefault());
            node.setCreatedDate(created.format(DATE_FORMATTER));
            node.setLastModified(modified.format(DATE_FORMATTER));
            node.setOwnerName(FALLBACK_OWNER);
        } catch (IOException e) {
            node.setCreatedDate("Unknown");
            node.setOwnerName(FALLBACK_OWNER);
        }
    }

    public boolean pathExists(String relativePath) {
        try {
            Path fullPath = getServingDirectoryPath().resolve(relativePath).normalize();
            validatePathSecurity(fullPath, relativePath);
            return Files.exists(fullPath);
        } catch (IOException e) {
            return false;
        }
    }

    public DownloadableResource getResourceForPath(String relativePath) throws IOException {
        Path rootPath = getServingDirectoryPath();
        Path fullPath = rootPath.resolve(relativePath).normalize();

        if (!fullPath.startsWith(rootPath)) {
            throw new IOException("Path Traversal Attempt Forbidden: " + relativePath);
        }

        File file = fullPath.toFile();
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + relativePath);
        }

        updateLastAccessedTime(relativePath);

        if (file.isDirectory()) {
            return createZipResourceForDirectory(file);

        } else {
            return createResourceForFile(fullPath, file);
        }
    }

    private void updateLastAccessedTime(String relativePath) {
        try {
            Optional<FileMetadata> metadata = fileMetadataRepository.findByPath(relativePath);
            metadata.ifPresent(m -> {
                m.setLastAccessed(LocalDateTime.now());
                fileMetadataRepository.save(m);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private DownloadableResource createZipResourceForDirectory(File directory) throws IOException {
        String zipFilename = directory.getName() + ".zip";
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
            zipDirectory(directory, directory.getName(), zipOutputStream);
        }

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
        Resource zipResource = new InputStreamResource(byteArrayInputStream);
        return new DownloadableResource(zipResource, zipFilename, "application/zip");
    }

    private DownloadableResource createResourceForFile(Path fullPath, File file) throws IOException {
        Resource resource = new org.springframework.core.io.UrlResource(fullPath.toUri());
        String contentType = Files.probeContentType(fullPath);
        if (contentType == null) {
            contentType = "application/octet-stream";
        }
        return new DownloadableResource(resource, file.getName(), contentType);
    }

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

    public void saveUploadedFiles(MultipartFile[] files, String subpath) throws IOException {
        Path destinationFolder = getServingDirectoryPath().resolve(subpath).normalize();
        validatePathSecurity(destinationFolder, subpath);

        if (!Files.exists(destinationFolder)) {
            Files.createDirectories(destinationFolder);
        }

        User currentUser = authService.getMainUser().orElse(null);

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }

            String originalFilename = file.getOriginalFilename();

            try {
                if (originalFilename == null || originalFilename.contains("..")) {
                    throw new IOException("Invalid filename: " + originalFilename);
                }

                Path destinationFile = destinationFolder.resolve(originalFilename).normalize();

                if (!destinationFile.getParent().equals(destinationFolder)) {
                    throw new IOException("Invalid destination path in filename: " + originalFilename);
                }

                String relativeFilePath = subpath.isEmpty() ? originalFilename : subpath + "/" + originalFilename;
                Optional<FileMetadata> existingMetadata = fileMetadataRepository.findByPath(relativeFilePath);

                if (existingMetadata.isPresent() && Files.exists(destinationFile)) {
                    saveVersion(existingMetadata.get(), destinationFile);
                }

                Files.copy(file.getInputStream(), destinationFile, StandardCopyOption.REPLACE_EXISTING);

                // DB Integration: Success
                try {

                    FileMetadata metadata = fileMetadataRepository.findByPath(relativeFilePath)
                            .orElse(new FileMetadata(relativeFilePath, originalFilename, file.getSize(), false,
                                    currentUser));

                    metadata.setSize(file.getSize());
                    metadata.setUpdatedAt(java.time.LocalDateTime.now());

                    FileMetadata savedMetadata = fileMetadataRepository.save(metadata);

                    FileHistory history = new FileHistory(savedMetadata, FileHistory.ChangeType.UPLOAD, currentUser);
                    fileHistoryRepository.save(history);

                } catch (Exception e) {
                    // Log error but don't fail the request if just DB fails, or should i?
                    // For now just log print stack trace as before for DB errors,
                    // BUT if the file copy itself failed, i want to capture that in history if
                    // possible (though i might not have a file record yet).
                    System.err.println("Failed to save DB metadata for: " + originalFilename);
                    e.printStackTrace();
                }

            } catch (Exception e) {
                // Log Failure
                String failedPath = subpath.isEmpty() ? originalFilename : subpath + "/" + originalFilename;
                FileHistory failure = new FileHistory(failedPath, originalFilename, FileHistory.ChangeType.UPLOAD,
                        currentUser, false, e.getMessage());
                fileHistoryRepository.save(failure);
                throw e; // Rethrow to notify controller
            }
        }
    }

    private void saveVersion(FileMetadata metadata, Path currentFilePath) throws IOException {
        File currentFile = currentFilePath.toFile();
        Path versionsDir = getServingDirectoryPath().resolve(VERSIONS_DIR_NAME);
        if (!Files.exists(versionsDir)) {
            Files.createDirectories(versionsDir);
        }

        int currentVer = metadata.getCurrentVersion() != null ? metadata.getCurrentVersion() : 1;
        String versionFilename = metadata.getFilename() + ".v" + currentVer;

        Path versionPath = versionsDir.resolve(versionFilename);
        while (Files.exists(versionPath)) {
            currentVer++;
            versionFilename = metadata.getFilename() + ".v" + currentVer;
            versionPath = versionsDir.resolve(versionFilename);
            metadata.setCurrentVersion(currentVer);
        }

        Files.move(currentFile.toPath(), versionPath, StandardCopyOption.REPLACE_EXISTING);

        FileVersion fileVersion = new FileVersion(
                metadata,
                currentVer,
                versionFilename,
                Files.size(versionPath),
                authService.getMainUser().orElse(null));
        fileVersionRepository.save(fileVersion);

        metadata.setCurrentVersion(currentVer + 1);
        fileMetadataRepository.save(metadata);

        List<FileVersion> versions = fileVersionRepository.findByFileMetadataOrderByVersionDesc(metadata);
        if (versions.size() > MAX_FILE_VERSIONS) {
            for (int i = MAX_FILE_VERSIONS; i < versions.size(); i++) {
                FileVersion oldVersion = versions.get(i);
                try {
                    Files.deleteIfExists(versionsDir.resolve(oldVersion.getStoredFilename()));
                    fileVersionRepository.delete(oldVersion);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void restoreVersion(Long fileId, Integer version, RestoreMode mode) throws IOException {
        FileMetadata metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new FileNotFoundException("File not found"));

        List<FileVersion> versions = fileVersionRepository.findByFileMetadataOrderByVersionDesc(metadata);
        FileVersion versionToRestore = versions.stream()
                .filter(v -> v.getVersion().equals(version))
                .findFirst()
                .orElseThrow(() -> new FileNotFoundException("Version not found"));

        Path versionsDir = getServingDirectoryPath().resolve(VERSIONS_DIR_NAME);
        Path versionPath = versionsDir.resolve(versionToRestore.getStoredFilename());

        if (!Files.exists(versionPath)) {
            throw new FileNotFoundException("Version file missing on disk");
        }

        User currentUser = authService.getMainUser().orElse(null);

        if (mode == RestoreMode.COPY) {
            // Restore as COPY
            String originalName = metadata.getFilename();
            String nameWithoutExt = originalName;
            String ext = "";
            int lastDot = originalName.lastIndexOf(".");
            if (lastDot > 0) {
                nameWithoutExt = originalName.substring(0, lastDot);
                ext = originalName.substring(lastDot);
            }

            String newFilename = nameWithoutExt + "_v" + version + ext;
            String newRelativePath = "";

            // Handle parent directory in path
            Path oldPath = Paths.get(metadata.getPath());
            if (oldPath.getParent() != null) {
                newRelativePath = oldPath.getParent().toString() + "/" + newFilename;
            } else {
                newRelativePath = newFilename;
            }

            Path newFullPath = getServingDirectoryPath().resolve(newRelativePath).normalize();
            validatePathSecurity(newFullPath, newRelativePath);

            Files.copy(versionPath, newFullPath, StandardCopyOption.REPLACE_EXISTING);

            // Create new metadata
            FileMetadata newMetadata = new FileMetadata(
                    newRelativePath,
                    newFilename,
                    Files.size(newFullPath),
                    false,
                    currentUser);
            fileMetadataRepository.save(newMetadata);

            FileHistory history = new FileHistory(newMetadata, FileHistory.ChangeType.UPLOAD, currentUser);
            history.setErrorMessage("Restored from " + metadata.getFilename() + " (v" + version + ")");
            fileHistoryRepository.save(history);

        } else {
            // Restore as OVERWRITE
            Path currentFilePath = getServingDirectoryPath().resolve(metadata.getPath());
            File currentFile = currentFilePath.toFile();

            if (currentFile.exists()) {
                saveVersion(metadata, currentFilePath);
            }

            Files.copy(versionPath, currentFilePath, StandardCopyOption.REPLACE_EXISTING);

            metadata.setSize(Files.size(currentFilePath));
            metadata.setUpdatedAt(LocalDateTime.now());
            fileMetadataRepository.save(metadata);

            FileHistory history = new FileHistory(metadata, FileHistory.ChangeType.UPLOAD, currentUser);
            history.setErrorMessage("Restored from version " + version);
            fileHistoryRepository.save(history);
        }
    }

    public void deleteItem(String relativePath) throws IOException {
        Path fullPath = getServingDirectoryPath().resolve(relativePath).normalize();
        User currentUser = authService.getMainUser().orElse(null);
        String filename = "unknown";

        try {
            validatePathSecurity(fullPath, relativePath);

            File itemToDelete = fullPath.toFile();
            if (!itemToDelete.exists()) {
                throw new FileNotFoundException("Item not found: " + relativePath);
            }

            filename = itemToDelete.getName();

            if (itemToDelete.isDirectory()) {
                deleteRecursively(itemToDelete);
                recordDeletion(relativePath, filename, currentUser);
            } else {
                if (!itemToDelete.delete()) {
                    throw new IOException("Failed to delete file: " + relativePath);
                }
                recordDeletion(relativePath, filename, currentUser);
            }
        } catch (Exception e) {
            FileHistory failure = new FileHistory(relativePath, filename, FileHistory.ChangeType.DELETE, currentUser,
                    false, e.getMessage());
            fileHistoryRepository.save(failure);
            throw e;
        }
    }

    private void recordDeletion(String path, String filename, User user) {
        try {
            Optional<FileMetadata> metadataOpt = fileMetadataRepository.findByPath(path);
            if (metadataOpt.isPresent()) {
                FileMetadata metadata = metadataOpt.get();

                // Archive history before deleting metadata
                FileHistory history = new FileHistory(path, filename, FileHistory.ChangeType.DELETE, user);
                fileHistoryRepository.save(history);

                fileMetadataRepository.delete(metadata);
            } else {
                // Even if metadata missing, record history of deletion attempt/success
                FileHistory history = new FileHistory(path, filename, FileHistory.ChangeType.DELETE, user);
                fileHistoryRepository.save(history);
            }
        } catch (Exception e) {
            System.err.println("Failed to update DB for deletion: " + path);
            e.printStackTrace();
        }
    }

    private void deleteRecursively(File file) throws IOException {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteRecursively(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed to delete: " + file);
        }
    }

    public void createDirectory(String relativePath, String directoryName) throws IOException {
        User currentUser = authService.getMainUser().orElse(null);

        try {
            Path parentPath = getServingDirectoryPath().resolve(relativePath).normalize();
            validatePathSecurity(parentPath, relativePath);

            if (directoryName == null || directoryName.trim().isEmpty() || directoryName.contains("..")
                    || directoryName.contains("/") || directoryName.contains("\\")) {
                throw new IOException("Invalid directory name: " + directoryName);
            }

            Path newDirPath = parentPath.resolve(directoryName).normalize();

            if (!newDirPath.getParent().equals(parentPath)) {
                throw new IOException("Invalid directory path");
            }

            if (Files.exists(newDirPath)) {
                throw new IOException("Directory already exists: " + directoryName);
            }

            Files.createDirectories(newDirPath);

            try {
                String fullRelativePath = relativePath.isEmpty() ? directoryName : relativePath + "/" + directoryName;

                FileMetadata metadata = new FileMetadata(fullRelativePath, directoryName, 0L, true, currentUser);
                FileMetadata savedMetadata = fileMetadataRepository.save(metadata);

                FileHistory history = new FileHistory(savedMetadata, FileHistory.ChangeType.CREATE_FOLDER, currentUser);
                fileHistoryRepository.save(history);

            } catch (Exception e) {
                System.err.println("Failed to save DB metadata for dir: " + directoryName);
                e.printStackTrace();
            }

        } catch (Exception e) {
            String fullRelativePath = relativePath.isEmpty() ? directoryName : relativePath + "/" + directoryName;
            FileHistory failure = new FileHistory(fullRelativePath, directoryName, FileHistory.ChangeType.CREATE_FOLDER,
                    currentUser, false, e.getMessage());
            fileHistoryRepository.save(failure);
            throw e;
        }
    }
}