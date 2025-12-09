package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean isSetupRequired() {
        return userRepository.count() == 0;
    }

    public void setupUser(String username, String rawPassword) {
        if (!isSetupRequired()) {
            throw new RuntimeException("Setup already completed");
        }
        String encodedPassword = passwordEncoder.encode(rawPassword);
        User user = new User(username, encodedPassword, "ROLE_USER");
        userRepository.save(user);
    }

    // Helper to get the single user for now
    public Optional<User> getMainUser() {
        return userRepository.findAll().stream().findFirst();
    }
}