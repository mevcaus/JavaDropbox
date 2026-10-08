package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.config.LoginAttemptLimiter;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.AccountService;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** What admins do to accounts, and what the people they invite do with the links they are sent. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Managing accounts")
class AdminIntegrationTests {

  private static final RequestPostProcessor ADMIN = user("root").roles("ADMIN");
  private static final String PASSWORD = "correct horse battery";

  @TempDir static Path servingDir;

  @DynamicPropertySource
  static void overrideServingDirectory(DynamicPropertyRegistry registry) {
    registry.add("javadropbox.serving.directory", () -> servingDir.toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AccountService accountService;
  @Autowired private LoginAttemptLimiter limiter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

  private User root;
  private User bob;

  @BeforeEach
  void setUp() {
    root = users.save(new User("root", passwordEncoder.encode(PASSWORD), User.ROLE_ADMIN));
    bob = users.save(new User("bob", passwordEncoder.encode(PASSWORD), User.ROLE_USER));
    // The failed sign-ins below all come from MockMvc's one address.
    limiter.recordSuccess("127.0.0.1");
  }

  @AfterEach
  void tearDown() {
    TestDatabase.wipe(jdbc);
  }

  @Test
  @DisplayName("admins see every account with its role, whether it can sign in, quota and use")
  void accountsAreListed() throws Exception {
    bob.setQuotaBytes(1024L);
    users.save(bob);

    mockMvc
        .perform(get("/api/admin/users").with(ADMIN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].username").value("bob"))
        .andExpect(jsonPath("$[0].role").value("USER"))
        .andExpect(jsonPath("$[0].enabled").value(true))
        .andExpect(jsonPath("$[0].quotaBytes").value(1024))
        .andExpect(jsonPath("$[0].usedBytes").value(0))
        .andExpect(jsonPath("$[1].username").value("root"))
        .andExpect(jsonPath("$[1].role").value("ADMIN"))
        .andExpect(jsonPath("$[1].quotaBytes").doesNotExist());
  }

  // --- invitations ---------------------------------------------------------------

  @Test
  @DisplayName("an invitation creates its account once, with the role and quota it was made with")
  void invitationCreatesTheAccount() throws Exception {
    String token = invite("carol", "USER", "1MB");

    mockMvc
        .perform(get("/invite/" + token + "/info").with(anonymous()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("carol"));
    mockMvc
        .perform(post("/invite/" + token).param("password", "short").with(anonymous()).with(csrf()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/invite/" + token).param("password", PASSWORD).with(anonymous()).with(csrf()))
        .andExpect(status().isOk());

    signIn("carol", PASSWORD)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("USER"));
    User carol = users.findByUsername("carol").orElseThrow();
    assertThat(carol.getRole()).isEqualTo(User.ROLE_USER);
    assertThat(carol.getQuotaBytes()).isEqualTo(1024 * 1024);

    mockMvc
        .perform(
            post("/invite/" + token).param("password", PASSWORD).with(anonymous()).with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/invite/" + token + "/info").with(anonymous()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/admin/invites").with(ADMIN))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("an invitation can make an admin")
  void invitationCanMakeAnAdmin() throws Exception {
    String token = invite("dana", "ADMIN", "");
    mockMvc
        .perform(
            post("/invite/" + token).param("password", PASSWORD).with(anonymous()).with(csrf()))
        .andExpect(status().isOk());

    signIn("dana", PASSWORD).andExpect(jsonPath("$.role").value("ADMIN"));
    assertThat(users.findByUsername("dana").orElseThrow().getQuotaBytes()).isNull();
  }

  @Test
  @DisplayName("an invitation for a username in use is refused, and a new one replaces the old")
  void invitationsAreOnePerUsername() throws Exception {
    mockMvc
        .perform(post("/api/admin/invites").param("username", "bob").with(ADMIN).with(csrf()))
        .andExpect(status().isConflict());

    String first = invite("erin", "USER", "");
    String second = invite("erin", "USER", "");

    mockMvc
        .perform(get("/invite/" + first + "/info").with(anonymous()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/invite/" + second + "/info").with(anonymous()))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/admin/invites").with(ADMIN))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].username").value("erin"))
        .andExpect(jsonPath("$[0].createdBy").value("root"));
  }

  @Test
  @DisplayName("a withdrawn or expired invitation no longer works, nor is it listed")
  void withdrawnAndExpiredInvitationsDoNotWork() throws Exception {
    String withdrawn = invite("frank", "USER", "");
    long id =
        json.readTree(
                mockMvc
                    .perform(get("/api/admin/invites").with(ADMIN))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get(0)
            .get("id")
            .asLong();
    mockMvc
        .perform(delete("/api/admin/invites/" + id).with(ADMIN).with(csrf()))
        .andExpect(status().isOk());

    String expired = invite("gina", "USER", "");
    jdbc.update("UPDATE account_links SET expires_at = ?", Instant.now().minusSeconds(1));

    for (String token : new String[] {withdrawn, expired}) {
      mockMvc
          .perform(
              post("/invite/" + token).param("password", PASSWORD).with(anonymous()).with(csrf()))
          .andExpect(status().isNotFound());
    }
    assertThat(users.findByUsername("frank")).isEmpty();
    assertThat(users.findByUsername("gina")).isEmpty();
    mockMvc
        .perform(get("/api/admin/invites").with(ADMIN))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("an invitation link does not reset a password, nor a reset link create an account")
  void linksOnlyWorkForTheirPurpose() throws Exception {
    String invitation = invite("hank", "USER", "");
    String reset = passwordReset(bob);

    mockMvc
        .perform(get("/reset-password/" + invitation + "/info").with(anonymous()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/invite/" + reset + "/info").with(anonymous()))
        .andExpect(status().isNotFound());
  }

  // --- disabling ---------------------------------------------------------------------

  @Test
  @DisplayName("a disabled account cannot sign in and its session ends at once; enabled, it can")
  void disablingEndsSessionsAndSignIns() throws Exception {
    MockHttpSession session = session("bob");
    mockMvc.perform(get("/api/files").session(session)).andExpect(status().isOk());

    setEnabled(bob, false).andExpect(status().isOk());

    mockMvc.perform(get("/api/files").session(session)).andExpect(status().isUnauthorized());
    signIn("bob", PASSWORD).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/admin/users").with(ADMIN))
        .andExpect(jsonPath("$[?(@.username == 'bob')].enabled").value(false));

    setEnabled(bob, true).andExpect(status().isOk());
    signIn("bob", PASSWORD).andExpect(status().isOk());
  }

  @Test
  @DisplayName("an admin cannot disable their own account or change its role")
  void adminsCannotLockThemselvesOut() throws Exception {
    setEnabled(root, false).andExpect(status().isConflict());
    mockMvc
        .perform(
            put("/api/admin/users/" + root.getId() + "/role")
                .param("role", "USER")
                .with(ADMIN)
                .with(csrf()))
        .andExpect(status().isConflict());

    assertThat(users.findById(root.getId()).orElseThrow().isAdmin()).isTrue();
  }

  @Test
  @WithMockUser(username = "bob")
  @DisplayName("the last admin who can sign in is never disabled or made a user")
  void thereIsAlwaysAnAdmin() {
    // Called directly, as if by another admin whose own rights were taken away meanwhile.
    assertThatThrownBy(() -> accountService.setEnabled(root.getId(), false))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> accountService.setRole(root.getId(), "USER"))
        .isInstanceOf(ConflictException.class);

    assertThat(users.findById(root.getId()).orElseThrow())
        .satisfies(admin -> assertThat(admin.isAdmin()).isTrue())
        .satisfies(admin -> assertThat(admin.isEnabled()).isTrue());
  }

  // --- roles and quotas ----------------------------------------------------------------

  @Test
  @DisplayName("a new role ends the account's sessions, and it signs back in with that role")
  void roleChangeEndsSessions() throws Exception {
    MockHttpSession session = session("bob");
    mockMvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());

    mockMvc
        .perform(
            put("/api/admin/users/" + bob.getId() + "/role")
                .param("role", "ADMIN")
                .with(ADMIN)
                .with(csrf()))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    MockHttpSession again = session("bob");
    mockMvc.perform(get("/api/admin/users").session(again)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("a quota is set as a size or in bytes, cleared when empty, and refused otherwise")
  void quotasAreSet() throws Exception {
    setQuota(bob, "5GB").andExpect(status().isOk());
    assertThat(users.findById(bob.getId()).orElseThrow().getQuotaBytes())
        .isEqualTo(5L * 1024 * 1024 * 1024);

    setQuota(bob, "1500").andExpect(status().isOk());
    assertThat(users.findById(bob.getId()).orElseThrow().getQuotaBytes()).isEqualTo(1500);

    setQuota(bob, "").andExpect(status().isOk());
    assertThat(users.findById(bob.getId()).orElseThrow().getQuotaBytes()).isNull();

    setQuota(bob, "lots").andExpect(status().isBadRequest());
    setQuota(bob, "0").andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/admin/users/999999/quota").param("quota", "1MB").with(ADMIN).with(csrf()))
        .andExpect(status().isNotFound());
  }

  // --- password resets -----------------------------------------------------------------

  @Test
  @DisplayName("a reset link sets a new password once, and ends the account's sessions")
  void passwordReset() throws Exception {
    MockHttpSession session = session("bob");
    String token = passwordReset(bob);

    // Until the link is used, nothing has changed.
    mockMvc.perform(get("/api/files").session(session)).andExpect(status().isOk());
    mockMvc
        .perform(get("/reset-password/" + token + "/info").with(anonymous()))
        .andExpect(jsonPath("$.username").value("bob"));

    mockMvc
        .perform(
            post("/reset-password/" + token)
                .param("password", "a brand new password")
                .with(anonymous())
                .with(csrf()))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/files").session(session)).andExpect(status().isUnauthorized());
    signIn("bob", PASSWORD).andExpect(status().isUnauthorized());
    signIn("bob", "a brand new password").andExpect(status().isOk());
    mockMvc
        .perform(
            post("/reset-password/" + token)
                .param("password", "yet another password")
                .with(anonymous())
                .with(csrf()))
        .andExpect(status().isNotFound());
  }

  private String invite(String username, String role, String quota) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/admin/invites")
                    .param("username", username)
                    .param("role", role)
                    .param("quota", quota)
                    .with(ADMIN)
                    .with(csrf()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return tokenOf(json.readTree(body));
  }

  private String passwordReset(User account) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/admin/users/" + account.getId() + "/password-reset")
                    .with(ADMIN)
                    .with(csrf()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return tokenOf(json.readTree(body));
  }

  private static String tokenOf(JsonNode link) {
    String url = link.get("url").asText();
    return url.substring(url.lastIndexOf('/') + 1);
  }

  private ResultActions setEnabled(User account, boolean enabled) throws Exception {
    return mockMvc.perform(
        put("/api/admin/users/" + account.getId() + "/enabled")
            .param("enabled", String.valueOf(enabled))
            .with(ADMIN)
            .with(csrf()));
  }

  private ResultActions setQuota(User account, String quota) throws Exception {
    return mockMvc.perform(
        put("/api/admin/users/" + account.getId() + "/quota")
            .param("quota", quota)
            .with(ADMIN)
            .with(csrf()));
  }

  private ResultActions signIn(String username, String password) throws Exception {
    return mockMvc.perform(
        post("/login")
            .param("username", username)
            .param("password", password)
            .with(anonymous())
            .with(csrf()));
  }

  // A real session, signed in through the login form like a browser's.
  private MockHttpSession session(String username) throws Exception {
    return (MockHttpSession)
        signIn(username, PASSWORD)
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
  }
}
