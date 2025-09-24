package com.javadropbox.javadropbox.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

@Service
public class AuthService {

    private final File userFile = new File("user.properties");
    private final Properties userProperties = new Properties();
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private boolean setupRequired;

    public AuthService() throws IOException {
        setupRequired = !userFile.exists();
        if (!setupRequired) {
            try (FileInputStream in = new FileInputStream(userFile)) {
                userProperties.load(in);
            }
        }
    }

    public boolean isSetupRequired() {
        return setupRequired;
    }

    public void completeSetup(String username, String password) throws IOException {
        if (!isSetupRequired()) {
            throw new IllegalStateException("Setup has already been completed.");
        }

        String hashedPassword = passwordEncoder.encode(password);
        userProperties.setProperty("user.username", username);
        userProperties.setProperty("user.password", hashedPassword);

        try (FileOutputStream out = new FileOutputStream(userFile)) {
            userProperties.store(out, "JavaDropbox User Credentials");
        }

        this.setupRequired = false;
        System.out.println("✅ Setup complete. User credentials saved.");
    }

    public boolean authenticate(String username, String rawPassword) {
        if (isSetupRequired()) {
            return false;
        }
        String storedUsername = userProperties.getProperty("user.username");
        String storedHashedPassword = userProperties.getProperty("user.password");

        if (storedUsername == null || !storedUsername.equals(username) || storedHashedPassword == null) {
            return false;
        }

        return passwordEncoder.matches(rawPassword, storedHashedPassword);
    }


    public String getStoredUsername() {
        return userProperties.getProperty("user.username");
    }

    public String getStoredPasswordHash() {
        return userProperties.getProperty("user.password");
    }
}