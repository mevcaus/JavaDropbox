package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.setup.required=false", "app.setup.filter.enabled=true"})
@DisplayName("Authentication Integration Tests")
class AuthIntegrationTests {

  private static final String CSRF_COOKIE = "XSRF-TOKEN";
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";

  @Autowired private MockMvc mockMvc;

  @Autowired private UserRepository userRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @BeforeEach
  void setUp() {
    if (userRepository.count() == 0) {
      userRepository.save(new User("testadmin", passwordEncoder.encode("password"), "ROLE_ADMIN"));
    }
  }

  @AfterEach
  void tearDown() {
    userRepository.deleteAll();
  }

  @Test
  @DisplayName("Unauthenticated user receives 401 for /api/me")
  void unauthenticatedUserReceives401() throws Exception {
    mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Authenticated user receives username for /api/me")
  @WithMockUser(
      username = "testuser",
      roles = {"USER"})
  void authenticatedUserReceivesUsername() throws Exception {
    mockMvc
        .perform(get("/api/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username", is("testuser")));
  }

  @Test
  @DisplayName("State-changing request is rejected without CSRF token")
  @WithMockUser(
      username = "testuser",
      roles = {"USER"})
  void stateChangingRequestRejectedWithoutCsrf() throws Exception {
    mockMvc.perform(post("/api/share").param("path", "some.txt")).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("A safe request hands the browser an XSRF-TOKEN cookie")
  @WithMockUser(
      username = "testuser",
      roles = {"USER"})
  void safeRequestIssuesCsrfCookie() throws Exception {
    Cookie cookie =
        mockMvc.perform(get("/api/me")).andReturn().getResponse().getCookie(CSRF_COOKIE);

    assertThat(cookie).as("SPA has no way to obtain a CSRF token without this cookie").isNotNull();
    assertThat(cookie.getValue()).isNotEmpty();
    assertThat(cookie.isHttpOnly()).as("the SPA must be able to read this cookie").isFalse();
  }

  @Test
  @DisplayName("State-changing request is accepted using the token from the cookie")
  @WithMockUser(
      username = "testuser",
      roles = {"USER"})
  void stateChangingRequestAcceptedWithCookieToken() throws Exception {
    // Drive the same round trip the browser does -- read the token from the cookie and send it
    // back in the header -- rather than using .with(csrf()), which injects the token directly and
    // would stay green even if the cookie were never issued at all.
    Cookie cookie =
        mockMvc.perform(get("/api/me")).andReturn().getResponse().getCookie(CSRF_COOKIE);
    assertThat(cookie).isNotNull();

    mockMvc
        .perform(
            post("/api/share")
                .param("path", "some.txt")
                .cookie(cookie)
                .header(CSRF_HEADER, cookie.getValue()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("An unauthenticated caller can obtain a token and log in with it")
  void loginAcceptsTokenFromCookie() throws Exception {
    // The SPA's boot call is the only request it makes before logging in, so the 401 it gets back
    // has to carry the token -- otherwise there is no way to ever reach a successful login.
    MvcResult primer =
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andReturn();
    Cookie cookie = primer.getResponse().getCookie(CSRF_COOKIE);
    assertThat(cookie).as("login is unreachable if the 401 carries no CSRF token").isNotNull();

    mockMvc
        .perform(
            post("/login")
                .param("username", "testadmin")
                .param("password", "password")
                .cookie(cookie)
                .header(CSRF_HEADER, cookie.getValue()))
        .andExpect(status().isOk());
  }
}
