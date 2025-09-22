package com.javadropbox.javadropbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
public class JavadropboxApplication {

    private static final String DEFAULT_JDB_FOLDER = "JDB";

    public static void main(String[] args) {
        String servingDirectory = getServingDirectory(args);
        System.setProperty("javadropbox.serving.directory", servingDirectory);
        SpringApplication.run(JavadropboxApplication.class, args);

        // Show startup message
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🚀 JavaDropbox is now running!");
        System.out.println("📁 Serving directory: " + servingDirectory);
        System.out.println("🌐 Web interface: http://localhost:8080");
        System.out.println("=".repeat(60));
    }

    private static String getServingDirectory(String[] args) {
        // Check if directory was provided as command line argument
        if (args.length > 0 && args[0].startsWith("--directory=")) {
            String customDirectory = args[0].substring("--directory=".length());
            return validateAndCreateDirectory(customDirectory);
        } else if (args.length > 0 && !args[0].startsWith("--")) {
            return validateAndCreateDirectory(args[0]);
        }

        // Default: Create JDB folder in project directory
        return createDefaultJDBDirectory();
    }

    private static String createDefaultJDBDirectory() {
        String projectDir = System.getProperty("user.dir");
        Path jdbPath = Paths.get(projectDir, DEFAULT_JDB_FOLDER);
        File jdbDir = jdbPath.toFile();

        if (!jdbDir.exists()) {
            if (jdbDir.mkdirs()) {
                System.out.println("✅ Created JDB directory: " + jdbDir.getAbsolutePath());
                createWelcomeFile(jdbDir);
            } else {
                System.err.println("❌ Failed to create JDB directory. Using project directory instead.");
                return projectDir;
            }
        } else {
            System.out.println("📁 Using existing JDB directory: " + jdbDir.getAbsolutePath());
        }

        return jdbDir.getAbsolutePath();
    }

    private static void createWelcomeFile(File jdbDir) {
        try {
            File welcomeFile = new File(jdbDir, "Welcome to JavaDropbox.txt");
            if (!welcomeFile.exists()) {
                java.nio.file.Files.write(welcomeFile.toPath(),
                        ("Welcome to JavaDropbox!\n\n" +
                                "This is your file storage directory.\n" +
                                "You can place any files or folders here and they will be accessible through the web interface.\n\n" +
                                "Created on: " + java.time.LocalDateTime.now() + "\n").getBytes());
                System.out.println("📝 Created welcome file: " + welcomeFile.getName());
            }
        } catch (Exception e) {
            System.out.println("Note: Could not create welcome file (not a problem)");
        }
    }

    private static String validateAndCreateDirectory(String path) {
        File dir = new File(path);

        if (!dir.exists()) {
            if (dir.mkdirs()) {
                System.out.println("✅ Created directory: " + dir.getAbsolutePath());
            } else {
                System.err.println("❌ Failed to create directory: " + path);
                System.out.println("📁 Falling back to default JDB directory");
                return createDefaultJDBDirectory();
            }
        } else {
            System.out.println("📁 Using directory: " + dir.getAbsolutePath());
        }

        return dir.getAbsolutePath();
    }
}