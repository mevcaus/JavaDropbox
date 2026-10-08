package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import com.javadropbox.javadropbox.service.SearchIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.hamcrest.Matchers;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.FileSystemUtils;

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
  @Autowired private SearchIndex searchIndex;
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
  void tearDown() throws Exception {
    TestDatabase.wipe(jdbc);
    try (var entries = Files.list(servingDir)) {
      for (Path entry : entries.toList()) {
        FileSystemUtils.deleteRecursively(entry);
      }
    }
    searchIndex.reconcile();
    searchIndex.awaitIdle(Duration.ofSeconds(30));
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

  @Test
  @DisplayName("each account's use is what it stores itself, previous versions included")
  void usageIsEachAccountsOwn() throws Exception {
    upload(user("bob"), "notes.txt", 300);
    upload(user("bob"), "notes.txt", 200);
    upload(ADMIN, "mine.txt", 50);

    mockMvc
        .perform(get("/api/admin/users").with(ADMIN))
        .andExpect(jsonPath("$[0].username").value("bob"))
        .andExpect(jsonPath("$[0].usedBytes").value(300 + 200))
        .andExpect(jsonPath("$[1].username").value("root"))
        .andExpect(jsonPath("$[1].usedBytes").value(50));
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

    // Nor do they when used, and trying leaves both links working for what they are for.
    String bobsPassword = users.findById(bob.getId()).orElseThrow().getPassword();
    mockMvc
        .perform(
            post("/invite/" + reset).param("password", PASSWORD).with(anonymous()).with(csrf()))
        .andExpect(status().isNotFound());
    resetPassword(invitation, "a brand new password").andExpect(status().isNotFound());
    assertThat(users.count()).isEqualTo(2);
    assertThat(users.findByUsername("hank")).isEmpty();
    assertThat(users.findById(bob.getId()).orElseThrow().getPassword()).isEqualTo(bobsPassword);

    mockMvc
        .perform(
            post("/invite/" + invitation)
                .param("password", PASSWORD)
                .with(anonymous())
                .with(csrf()))
        .andExpect(status().isOk());
    resetPassword(reset, "a brand new password").andExpect(status().isOk());
    signIn("hank", PASSWORD).andExpect(status().isOk());
    signIn("bob", "a brand new password").andExpect(status().isOk());
  }

  @Test
  @DisplayName("an invitation's link opens /invite for 7 days, a reset's /reset-password for one")
  void linksOpenTheirPageUntilTheyExpire() throws Exception {
    Instant before = Instant.now();
    JsonNode invitation = link(inviting("hank", "USER", ""));
    JsonNode reset = link(resetting(bob));
    Instant after = Instant.now();

    // MockMvc's requests are to http://localhost; the links are on whatever host was asked.
    assertThat(invitation.get("url").asText()).matches("http://localhost/invite/[\\w-]{43}");
    assertThat(reset.get("url").asText()).matches("http://localhost/reset-password/[\\w-]{43}");
    assertThat(Instant.parse(invitation.get("expiresAt").asText()))
        .isBetween(before.plus(Duration.ofDays(7)), after.plus(Duration.ofDays(7)));
    assertThat(Instant.parse(reset.get("expiresAt").asText()))
        .isBetween(before.plus(Duration.ofDays(1)), after.plus(Duration.ofDays(1)));
  }

  @Test
  @DisplayName("an invitation for a username an account has taken since is refused, and kept")
  void invitationForATakenUsernameIsRefused() throws Exception {
    String token = invite("carol", "ADMIN", "1MB");
    // Created some other way meanwhile, such as by hand in the database.
    User carol =
        users.save(
            new User("carol", passwordEncoder.encode("carol's own password"), User.ROLE_USER));

    mockMvc
        .perform(
            post("/invite/" + token).param("password", PASSWORD).with(anonymous()).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(Matchers.containsString("already an account")));

    // The account is as it was: neither the invitation's password, nor its role or quota.
    assertThat(users.findByUsername("carol").orElseThrow())
        .satisfies(after -> assertThat(after.getPassword()).isEqualTo(carol.getPassword()))
        .satisfies(after -> assertThat(after.getRole()).isEqualTo(User.ROLE_USER))
        .satisfies(after -> assertThat(after.getQuotaBytes()).isNull());
    assertThat(users.count()).isEqualTo(3);
    signIn("carol", "carol's own password").andExpect(jsonPath("$.role").value("USER"));

    // Refusing it rolls everything back, the link's use with it: the invitation is still open,
    // and listed for an admin to withdraw.
    mockMvc.perform(get("/invite/" + token + "/info").with(anonymous())).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/admin/invites").with(ADMIN))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].username").value("carol"));
  }

  @Test
  @DisplayName("an unknown role or a quota that is no size is refused, and changes nothing")
  void badInputIsRefused() throws Exception {
    invite("ivan", "USER", "");

    mockMvc
        .perform(
            post("/api/admin/invites")
                .param("username", "jill")
                .param("role", "ROOT")
                .with(ADMIN)
                .with(csrf()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/admin/invites")
                .param("username", "jill")
                .param("quota", "lots")
                .with(ADMIN)
                .with(csrf()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/admin/invites").with(ADMIN))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].username").value("ivan"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM account_links", Long.class)).isEqualTo(1);

    setQuota(bob, "1500").andExpect(status().isOk());
    setRole(bob, "ROOT").andExpect(status().isBadRequest());
    setQuota(bob, "0").andExpect(status().isBadRequest());
    setQuota(bob, "-5MB").andExpect(status().isBadRequest());

    assertThat(users.findById(bob.getId()).orElseThrow())
        .satisfies(after -> assertThat(after.getRole()).isEqualTo(User.ROLE_USER))
        .satisfies(after -> assertThat(after.getQuotaBytes()).isEqualTo(1500))
        .satisfies(
            after -> assertThat(after.getSessionVersion()).isEqualTo(bob.getSessionVersion()));
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
  @DisplayName("disabling an admin withdraws the invitations and reset links they made, for good")
  void disablingAnAdminWithdrawsTheirLinks() throws Exception {
    User dana = users.save(new User("dana", passwordEncoder.encode(PASSWORD), User.ROLE_ADMIN));
    RequestPostProcessor asDana = user("dana").roles("ADMIN");
    String danasInvite =
        tokenOf(
            link(
                mockMvc.perform(
                    post("/api/admin/invites")
                        .param("username", "hank")
                        .with(asDana)
                        .with(csrf()))));
    String danasReset =
        tokenOf(
            link(
                mockMvc.perform(
                    post("/api/admin/users/" + bob.getId() + "/password-reset")
                        .with(asDana)
                        .with(csrf()))));
    String rootsInvite = invite("ivan", "USER", "");

    setEnabled(dana, false).andExpect(status().isOk());

    mockMvc
        .perform(get("/invite/" + danasInvite + "/info").with(anonymous()))
        .andExpect(status().isNotFound());
    resetPassword(danasReset, "a brand new password").andExpect(status().isNotFound());
    signIn("bob", PASSWORD).andExpect(status().isOk());
    // Links other admins made are not Dana's to take with her.
    mockMvc
        .perform(get("/invite/" + rootsInvite + "/info").with(anonymous()))
        .andExpect(status().isOk());

    // Enabling the account again does not bring them back.
    setEnabled(dana, true).andExpect(status().isOk());
    mockMvc
        .perform(get("/invite/" + danasInvite + "/info").with(anonymous()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("a session left idle while its account is disabled and enabled again has ended")
  void reenablingDoesNotBringSessionsBack() throws Exception {
    MockHttpSession session = session("bob");
    mockMvc.perform(get("/api/files").session(session)).andExpect(status().isOk());

    // No request from the session in between: by the time it makes one, the account is enabled
    // again, so only the session version can tell that it ended.
    setEnabled(bob, false).andExpect(status().isOk());
    setEnabled(bob, true).andExpect(status().isOk());

    mockMvc.perform(get("/api/files").session(session)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/files").session(session("bob"))).andExpect(status().isOk());
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

  @Test
  @DisplayName("an admin who is disabled does not count as one who can sign in")
  void disabledAdminsDoNotCount() throws Exception {
    User dana = users.save(new User("dana", passwordEncoder.encode(PASSWORD), User.ROLE_ADMIN));
    User erin = new User("erin", passwordEncoder.encode(PASSWORD), User.ROLE_ADMIN);
    erin.setEnabled(false);
    users.save(erin);

    // Root is left, so Dana can go.
    setEnabled(dana, false).andExpect(status().isOk());
    assertThat(users.findById(dana.getId()).orElseThrow().isEnabled()).isFalse();

    // Now Dana and Erin are admins who cannot sign in, and Root the only one who can. Someone whose
    // admin rights were taken away while they were still signed in tries to take Root's: were
    // disabled admins counted, there would seem to be two left.
    RequestPostProcessor formerAdmin = user("bob").roles("ADMIN");
    mockMvc
        .perform(
            put("/api/admin/users/" + root.getId() + "/enabled")
                .param("enabled", "false")
                .with(formerAdmin)
                .with(csrf()))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            put("/api/admin/users/" + root.getId() + "/role")
                .param("role", "USER")
                .with(formerAdmin)
                .with(csrf()))
        .andExpect(status().isConflict());

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
  @DisplayName("an admin can be made a user while another remains, and signs back in as one")
  void anAdminCanBeMadeAUser() throws Exception {
    User dana = users.save(new User("dana", passwordEncoder.encode(PASSWORD), User.ROLE_ADMIN));
    MockHttpSession session = session("dana");
    mockMvc.perform(get("/api/admin/users").session(session)).andExpect(status().isOk());

    setRole(dana, "USER").andExpect(status().isOk());

    assertThat(users.findById(dana.getId()).orElseThrow().getRole()).isEqualTo(User.ROLE_USER);
    mockMvc.perform(get("/api/admin/users").session(session)).andExpect(status().isUnauthorized());
    MockHttpSession again = session("dana");
    mockMvc.perform(get("/api/me").session(again)).andExpect(jsonPath("$.role").value("USER"));
    mockMvc.perform(get("/api/admin/users").session(again)).andExpect(status().isForbidden());
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

  @Test
  @DisplayName("a new reset link replaces the old one, and is not withdrawn as an invitation")
  void resetLinksAreOnePerAccount() throws Exception {
    String first = passwordReset(bob);
    String second = passwordReset(bob);

    resetPassword(first, "a brand new password").andExpect(status().isNotFound());
    signIn("bob", PASSWORD).andExpect(status().isOk());

    long id =
        jdbc.queryForObject(
            "SELECT id FROM account_links WHERE purpose = 'PASSWORD_RESET'", Long.class);
    mockMvc
        .perform(delete("/api/admin/invites/" + id).with(ADMIN).with(csrf()))
        .andExpect(status().isNotFound());

    resetPassword(second, "a brand new password").andExpect(status().isOk());
    signIn("bob", "a brand new password").andExpect(status().isOk());
  }

  @Test
  @DisplayName("an expired reset link no longer works, and the password stays as it was")
  void expiredResetLinksDoNotWork() throws Exception {
    String token = passwordReset(bob);
    jdbc.update("UPDATE account_links SET expires_at = ?", Instant.now().minusSeconds(1));

    mockMvc
        .perform(get("/reset-password/" + token + "/info").with(anonymous()))
        .andExpect(status().isNotFound());
    resetPassword(token, "a brand new password").andExpect(status().isNotFound());

    signIn("bob", "a brand new password").andExpect(status().isUnauthorized());
    signIn("bob", PASSWORD).andExpect(status().isOk());
  }

  private String invite(String username, String role, String quota) throws Exception {
    return tokenOf(link(inviting(username, role, quota)));
  }

  private ResultActions inviting(String username, String role, String quota) throws Exception {
    return mockMvc.perform(
        post("/api/admin/invites")
            .param("username", username)
            .param("role", role)
            .param("quota", quota)
            .with(ADMIN)
            .with(csrf()));
  }

  private String passwordReset(User account) throws Exception {
    return tokenOf(link(resetting(account)));
  }

  private ResultActions resetting(User account) throws Exception {
    return mockMvc.perform(
        post("/api/admin/users/" + account.getId() + "/password-reset").with(ADMIN).with(csrf()));
  }

  // The link a request that made one answered with.
  private JsonNode link(ResultActions made) throws Exception {
    return json.readTree(
        made.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private ResultActions resetPassword(String token, String password) throws Exception {
    return mockMvc.perform(
        post("/reset-password/" + token)
            .param("password", password)
            .with(anonymous())
            .with(csrf()));
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

  private ResultActions setRole(User account, String role) throws Exception {
    return mockMvc.perform(
        put("/api/admin/users/" + account.getId() + "/role")
            .param("role", role)
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

  private void upload(RequestPostProcessor as, String name, int size) throws Exception {
    mockMvc
        .perform(
            multipart("/api/files")
                .file(new MockMultipartFile("files", name, "text/plain", new byte[size]))
                .param("path", "")
                .with(as)
                .with(csrf().asHeader()))
        .andExpect(status().isOk());
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
