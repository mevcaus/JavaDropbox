package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("Account session filter")
class AccountSessionFilterTests {

  private final UserRepository users = mock(UserRepository.class);
  private final AccountSessionFilter filter = new AccountSessionFilter(users);
  private final User account = new User("ada", "{noop}secret-password", User.ROLE_ADMIN);
  private final MockHttpSession session = new MockHttpSession();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("a session carries on while its account is unchanged")
  void unchangedAccountKeepsItsSession() throws Exception {
    Authentication signIn = signInAs(new AccountDetails(account));

    filter.signedIn(requestInSession(), signIn);
    nextRequest(signIn);

    assertThat(session.isInvalid()).isFalse();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(signIn);
  }

  // The password check takes a while (BCrypt), between loading the account and recording the
  // session. Reading the version again afterwards would record the bumped one, and the session
  // would keep the role it signed in with.
  @Test
  @DisplayName("records the version the sign-in was checked against, not one bumped meanwhile")
  void changeDuringSignInEndsTheNewSession() throws Exception {
    Authentication signIn = signInAs(new AccountDetails(account));
    // An admin demotes the account while its password is being checked.
    account.setRole(User.ROLE_USER);
    account.endSessions();

    filter.signedIn(requestInSession(), signIn);
    nextRequest(signIn);

    assertThat(session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  private Authentication signInAs(AccountDetails details) {
    when(users.findByUsername("ada")).thenReturn(Optional.of(account));
    return UsernamePasswordAuthenticationToken.authenticated(
        details, null, details.getAuthorities());
  }

  private MockHttpServletRequest requestInSession() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);
    return request;
  }

  private void nextRequest(Authentication auth) throws Exception {
    SecurityContextHolder.getContext().setAuthentication(auth);
    filter.doFilter(requestInSession(), new MockHttpServletResponse(), new MockFilterChain());
  }
}
