package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.javadropbox.javadropbox.dto.ImportResult;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.OneTimeStepRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@DisplayName("Giving the first account an upgraded install's files, once")
class FirstAccountImportTests {

  @TempDir Path servingDir;

  private final UserRepository users = mock(UserRepository.class);
  // What the database has recorded, kept across "restarts" within a test.
  private final Set<String> recorded = new HashSet<>();
  private final OneTimeStepRepository steps = mock(OneTimeStepRepository.class);
  private final ImportService imports = mock(ImportService.class);
  private final SearchIndex searchIndex = mock(SearchIndex.class);
  private final User owner = new User("owner", "unused", User.ROLE_ADMIN);
  private LocalFileStore store;
  private StoragePaths storagePaths;
  private FirstAccountImport firstAccountImport;

  @BeforeEach
  void setUp() throws IOException {
    owner.setId(1L);
    store = spy(new LocalFileStore(servingDir));
    storagePaths = new StoragePaths(store, servingDir);
    firstAccountImport =
        new FirstAccountImport(storagePaths, store, users, steps, imports, searchIndex);
    when(users.findFirstByOrderByIdAsc()).thenReturn(Optional.of(owner));
    when(steps.existsById(anyString()))
        .thenAnswer(invocation -> recorded.contains(invocation.<String>getArgument(0)));
    doAnswer(invocation -> recorded.add(invocation.getArgument(0)))
        .when(steps)
        .recordDone(anyString(), any());
  }

  @Test
  @DisplayName("after an upgrade, the top of the store moves into the first account's folder")
  void upgradeMovesLooseItemsAndImportsThem() throws IOException {
    Files.writeString(servingDir.resolve("a.txt"), "a");
    Files.createDirectories(servingDir.resolve("docs"));
    Files.writeString(servingDir.resolve("docs/b.txt"), "b");
    Files.createDirectories(servingDir.resolve(".versions/10"));
    Files.writeString(servingDir.resolve(".versions/10/v1"), "older a");
    Files.writeString(servingDir.resolve(".DS_Store"), "");
    Files.createDirectories(servingDir.resolve("lost+found"));

    firstAccountImport.atStartup();

    Path home = home();
    assertThat(home.resolve("a.txt")).hasContent("a");
    assertThat(home.resolve("docs/b.txt")).hasContent("b");
    assertThat(servingDir.resolve("a.txt")).doesNotExist();
    assertThat(servingDir.resolve("docs")).doesNotExist();
    assertThat(servingDir.resolve(".versions/10/v1")).hasContent("older a");
    assertThat(servingDir.resolve(".DS_Store")).exists();
    assertThat(servingDir.resolve("lost+found")).isDirectory();
    assertThat(home.resolve("lost+found")).doesNotExist();
    verify(searchIndex).changed(any(Home.class), eq(""));
    verify(imports).importFiles(1L, "the upgrade");
    verify(steps).recordDone(eq(FirstAccountImport.STEP), any());
  }

  @Test
  @DisplayName("once it is done, nothing at the top of the store moves or is imported")
  void doneOnceThenLeftAlone() throws IOException {
    recorded.add(FirstAccountImport.STEP);
    recorded.add(FirstAccountImport.EVERY_ACCOUNT_STEP);
    Files.writeString(servingDir.resolve("later.txt"), "put here after the upgrade");

    firstAccountImport.atStartup();

    assertThat(servingDir.resolve("later.txt")).exists();
    assertThat(home().resolve("later.txt")).doesNotExist();
    verify(imports, never()).importFiles(anyLong(), anyString());
    verify(steps, never()).recordDone(any(), any());
  }

  @Test
  @DisplayName("an item whose name the account's folder already has stays where it is")
  void clashingItemStays() throws IOException {
    Files.writeString(home().resolve("a.txt"), "the account's own");
    Files.writeString(servingDir.resolve("a.txt"), "loose");

    firstAccountImport.atStartup();

    assertThat(home().resolve("a.txt")).hasContent("the account's own");
    assertThat(servingDir.resolve("a.txt")).hasContent("loose");
  }

  @Test
  @DisplayName("before there is any account, nothing moves and nothing is recorded")
  void nothingWithoutAnAccount() throws IOException {
    Files.writeString(servingDir.resolve("a.txt"), "a");
    when(users.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

    firstAccountImport.atStartup();

    assertThat(servingDir.resolve("a.txt")).exists();
    assertThat(servingDir.resolve(StoragePaths.HOMES_DIR)).doesNotExist();
    verify(steps, never()).recordDone(any(), any());
  }

  @Test
  @DisplayName("setup gives the new account what is at the top, imports it and records it done")
  void setupTakesInTheTopOfTheStore() throws IOException {
    Files.writeString(servingDir.resolve(StoragePaths.WELCOME_FILE), "welcome");

    firstAccountImport.forNewFirstAccount(owner);

    assertThat(home().resolve(StoragePaths.WELCOME_FILE)).hasContent("welcome");
    verify(imports).importFiles(1L, "setup");
    verify(steps).recordDone(eq(FirstAccountImport.STEP), any());
  }

  @Test
  @DisplayName("a move that fails isn't recorded as done, and is tried again at the next start")
  void failedMoveIsTriedAgain() throws IOException {
    Files.writeString(servingDir.resolve("a.txt"), "a");
    doThrow(new IOException("bucket unreachable")).when(store).move(anyString(), anyString());

    firstAccountImport.atStartup();

    assertThat(servingDir.resolve("a.txt")).exists();
    verify(steps, never()).recordDone(any(), any());
    verify(imports, never()).importFiles(anyLong(), anyString());

    doCallRealMethod().when(store).move(anyString(), anyString());
    firstAccountImport.atStartup();

    assertThat(home().resolve("a.txt")).hasContent("a");
    verify(steps).recordDone(eq(FirstAccountImport.STEP), any());
  }

  @Test
  @DisplayName("a top of the store that can't be listed isn't recorded as done")
  void unlistableTopIsTriedAgain() throws IOException {
    doThrow(new IOException("bucket unreachable")).when(store).list("");

    firstAccountImport.atStartup();

    verify(steps, never()).recordDone(any(), any());
  }

  @Test
  @DisplayName("a setup cut short, finished by the next start, keeps another database's files out")
  void setupCutShortStillLeavesAnotherDatabasesFiles() throws IOException {
    Files.writeString(home().resolve("theirs.txt"), "from a store used before");
    Files.writeString(servingDir.resolve(StoragePaths.WELCOME_FILE), "welcome");
    doThrow(new IOException("cut short")).when(store).move(anyString(), anyString());

    firstAccountImport.forNewFirstAccount(owner);
    doCallRealMethod().when(store).move(anyString(), anyString());
    firstAccountImport.atStartup();

    assertThat(home().resolve(StoragePaths.WELCOME_FILE)).exists();
    verify(imports, never()).importFiles(anyLong(), anyString());
    assertThat(recorded).contains(FirstAccountImport.STEP);
  }

  @Test
  @DisplayName("a setup cut short after moving the welcome file still imports it at the next start")
  void setupCutShortAfterTheMoveImportsAtTheNextStart() throws IOException {
    Files.writeString(servingDir.resolve(StoragePaths.WELCOME_FILE), "welcome");
    when(imports.importFiles(anyLong(), anyString())).thenThrow(new IllegalStateException("died"));

    firstAccountImport.forNewFirstAccount(owner);
    assertThat(recorded).doesNotContain(FirstAccountImport.STEP);
    doReturn(new ImportResult(1, 0, 0, 0)).when(imports).importFiles(anyLong(), anyString());
    firstAccountImport.atStartup();

    verify(imports).importFiles(1L, "the upgrade");
    assertThat(recorded).contains(FirstAccountImport.STEP);
  }

  @Test
  @DisplayName("an upgrade imports the first account's folder even though it already holds files")
  void upgradeImportsAFolderThatHoldsFiles() throws IOException {
    Files.writeString(home().resolve("moved at an earlier start.txt"), "x");

    firstAccountImport.atStartup();

    verify(imports).importFiles(1L, "the upgrade");
  }

  @Test
  @DisplayName("once done, a start names what is at the top, twenty at most")
  void startNamesLeftoverItems() throws IOException {
    recorded.add(FirstAccountImport.STEP);
    recorded.add(FirstAccountImport.EVERY_ACCOUNT_STEP);
    for (int i = 0; i < 25; i++) {
      Files.writeString(servingDir.resolve(String.format("later-%02d.txt", i)), "x");
    }

    Logger logger = (Logger) LoggerFactory.getLogger(FirstAccountImport.class);
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    logger.addAppender(logged);
    try {
      firstAccountImport.atStartup();
    } finally {
      logger.detachAppender(logged);
    }

    assertThat(logged.list).hasSize(1);
    assertThat(logged.list.get(0).getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.list.get(0).getFormattedMessage())
        .contains("25 items are at the top of")
        .contains("later-00.txt")
        .contains("and 5 more")
        .contains(".users/<account id>/");
  }

  @Test
  @DisplayName("setup leaves files another database's account left in the folder without rows")
  void setupDoesNotImportAnotherDatabasesFiles() throws IOException {
    Files.writeString(home().resolve("theirs.txt"), "from a store used before");
    Files.writeString(servingDir.resolve(StoragePaths.WELCOME_FILE), "welcome");

    firstAccountImport.forNewFirstAccount(owner);

    assertThat(home().resolve(StoragePaths.WELCOME_FILE)).exists();
    verify(imports, never()).importFiles(anyLong(), anyString());
    verify(steps).recordDone(eq(FirstAccountImport.STEP), any());
  }

  @Test
  @DisplayName("an import refused at the upgrade, as against the wrong store, is tried again")
  void refusedImportIsTriedAgain() throws IOException {
    when(imports.importFiles(anyLong(), anyString()))
        .thenThrow(new ConflictException("wrong store"));

    firstAccountImport.atStartup();

    assertThat(recorded).doesNotContain(FirstAccountImport.STEP);
  }

  @Test
  @DisplayName("with nothing to move, the search index is left alone")
  void nothingToMove() throws IOException {
    when(imports.importFiles(anyLong(), anyString())).thenReturn(new ImportResult(0, 0, 0, 0));

    firstAccountImport.atStartup();

    verify(searchIndex, never()).changed(any(Home.class), any());
  }

  @Test
  @DisplayName("after upgrading, every account's folder is imported, once")
  void everyAccountIsImportedOnce() throws IOException {
    User bob = new User("bob", "unused", User.ROLE_USER);
    bob.setId(2L);
    when(users.findAll()).thenReturn(List.of(owner, bob));

    firstAccountImport.atStartup();
    firstAccountImport.atStartup();

    verify(imports).importFiles(2L, "the upgrade");
    assertThat(recorded)
        .contains(
            FirstAccountImport.EVERY_ACCOUNT_STEP,
            FirstAccountImport.ACCOUNT_STEP_PREFIX + 1,
            FirstAccountImport.ACCOUNT_STEP_PREFIX + 2);
  }

  @Test
  @DisplayName("an account whose import fails is tried again, the others are not")
  void failedAccountIsTriedAgain() throws IOException {
    User bob = new User("bob", "unused", User.ROLE_USER);
    bob.setId(2L);
    when(users.findAll()).thenReturn(List.of(owner, bob));
    when(imports.importFiles(2L, "the upgrade")).thenThrow(new IllegalStateException("S3 down"));

    firstAccountImport.atStartup();

    assertThat(recorded)
        .contains(FirstAccountImport.ACCOUNT_STEP_PREFIX + 1)
        .doesNotContain(
            FirstAccountImport.ACCOUNT_STEP_PREFIX + 2, FirstAccountImport.EVERY_ACCOUNT_STEP);

    doReturn(new ImportResult(0, 0, 0, 0)).when(imports).importFiles(2L, "the upgrade");
    firstAccountImport.atStartup();

    assertThat(recorded).contains(FirstAccountImport.EVERY_ACCOUNT_STEP);
  }

  @Test
  @DisplayName("the first account's folder another database used stays unimported after upgrading")
  void foreignFirstFolderIsSkipped() throws IOException {
    Files.writeString(home().resolve("theirs.txt"), "from a store used before");
    when(users.findAll()).thenReturn(List.of(owner));

    firstAccountImport.forNewFirstAccount(owner);
    recorded.remove(FirstAccountImport.EVERY_ACCOUNT_STEP);
    firstAccountImport.atStartup();

    verify(imports, never()).importFiles(anyLong(), anyString());
  }

  @Test
  @DisplayName("an account refused once the store is known to be right isn't tried at every start")
  void refusedAccountIsLeftWithALogLine() throws IOException {
    User bob = new User("bob", "unused", User.ROLE_USER);
    bob.setId(2L);
    when(users.findAll()).thenReturn(List.of(owner, bob));
    when(imports.importFiles(2L, "the upgrade"))
        .thenThrow(new ConflictException("None of this account's files are in the store."));

    firstAccountImport.atStartup();

    assertThat(recorded).contains(FirstAccountImport.EVERY_ACCOUNT_STEP);
  }

  @Test
  @DisplayName("after upgrading, the first account's folder is walked once, not twice")
  void firstAccountIsImportedOnce() throws IOException {
    when(users.findAll()).thenReturn(List.of(owner));

    firstAccountImport.atStartup();

    verify(imports).importFiles(1L, "the upgrade");
  }

  @Test
  @DisplayName("setup records that there is no other account to import")
  void setupRecordsEveryAccountDone() throws IOException {
    firstAccountImport.forNewFirstAccount(owner);

    assertThat(recorded).contains(FirstAccountImport.EVERY_ACCOUNT_STEP);
  }

  private Path home() {
    return store.path(storagePaths.home(owner).key());
  }
}
