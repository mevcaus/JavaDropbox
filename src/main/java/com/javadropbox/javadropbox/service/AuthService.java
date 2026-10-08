package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private final UserRepository userRepository;

  public AuthService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  public boolean isSetupRequired() {
    return userRepository.count() == 0;
  }

  /**
   * The signed-in user, whom file operations are attributed to, or null for an anonymous request
   * (e.g. a share-link download) or a principal with no account row.
   */
  public User currentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      return null;
    }
    return userRepository.findByUsername(auth.getName()).orElse(null);
  }

  /**
   * The signed-in user, whose files a request acts on.
   *
   * @throws AuthenticationCredentialsNotFoundException if nobody is signed in, or the account is
   *     gone; Spring Security answers it with a 401
   */
  public User requireCurrentUser() {
    User user = currentUser();
    if (user == null) {
      throw new AuthenticationCredentialsNotFoundException("Not signed in");
    }
    return user;
  }
}
