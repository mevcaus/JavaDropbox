package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session its account may no longer use: the account was disabled, or its role changed or
 * its password was reset since the session signed in (see {@link User#getSessionVersion}). The
 * request carries on as anonymous, so it gets a 401 wherever a session is needed. Checking on every
 * request makes such a change take effect at once, rather than whenever the session would expire.
 *
 * <p>Created by SecurityConfig, ahead of the filter that makes requests without a session
 * anonymous, so it only runs inside the security chain.
 */
public class AccountSessionFilter extends OncePerRequestFilter {

  private static final String SESSION_VERSION =
      AccountSessionFilter.class.getName() + ".SESSION_VERSION";

  private static final Logger log = LoggerFactory.getLogger(AccountSessionFilter.class);

  private final UserRepository users;

  public AccountSessionFilter(UserRepository users) {
    this.users = users;
  }

  /** Records which version of the account a session that has just signed in belongs to. */
  public void signedIn(HttpServletRequest request, Authentication authentication) {
    users
        .findByUsername(authentication.getName())
        .ifPresent(
            account ->
                request.getSession().setAttribute(SESSION_VERSION, account.getSessionVersion()));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      User account = users.findByUsername(auth.getName()).orElse(null);
      HttpSession session = request.getSession(false);
      if (!mayContinue(account, session)) {
        log.info("Ended a session of \"{}\": the account has changed", auth.getName());
        SecurityContextHolder.clearContext();
        if (session != null) {
          session.invalidate();
        }
      }
    }
    filterChain.doFilter(request, response);
  }

  // A session with no version recorded was not signed in through the login form; only the account
  // itself can be checked then.
  private static boolean mayContinue(User account, HttpSession session) {
    if (account == null || !account.isEnabled()) {
      return false;
    }
    Object version = session == null ? null : session.getAttribute(SESSION_VERSION);
    return version == null || version.equals(account.getSessionVersion());
  }
}
