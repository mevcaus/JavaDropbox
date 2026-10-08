package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Files from before accounts had folders of their own")
class LooseFileAdoptionTests {

  @TempDir Path servingDir;

  private final UserRepository users = mock(UserRepository.class);
  private final SearchIndex searchIndex = mock(SearchIndex.class);
  private final User owner = new User("owner", "unused", User.ROLE_ADMIN);
  private StoragePaths storagePaths;
  private LooseFileAdoption adoption;

  @BeforeEach
  void setUp() throws IOException {
    owner.setId(1L);
    storagePaths = new StoragePaths(servingDir);
    adoption = new LooseFileAdoption(storagePaths, users, searchIndex);
  }

  @Test
  @DisplayName("everything at the top moves into the first account's folder, hidden names stay")
  void looseFilesMoveIntoTheFirstAccountsFolder() throws IOException {
    Files.writeString(servingDir.resolve("a.txt"), "a");
    Files.createDirectories(servingDir.resolve("docs"));
    Files.writeString(servingDir.resolve("docs/b.txt"), "b");
    Files.createDirectories(servingDir.resolve(".versions/10"));
    Files.writeString(servingDir.resolve(".versions/10/v1"), "older a");
    Files.writeString(servingDir.resolve(".DS_Store"), "");
    when(users.findFirstByOrderByIdAsc()).thenReturn(Optional.of(owner));

    adoption.adoptAtStartup();

    Path home = storagePaths.home(owner).root();
    assertThat(home.resolve("a.txt")).hasContent("a");
    assertThat(home.resolve("docs/b.txt")).hasContent("b");
    assertThat(servingDir.resolve("a.txt")).doesNotExist();
    assertThat(servingDir.resolve("docs")).doesNotExist();
    assertThat(servingDir.resolve(".versions/10/v1")).hasContent("older a");
    assertThat(servingDir.resolve(".DS_Store")).exists();
    verify(searchIndex).changed(any(Home.class), eq(""));
  }

  @Test
  @DisplayName("an item whose name the account's folder already has stays where it is")
  void clashingItemStays() throws IOException {
    Files.writeString(storagePaths.home(owner).root().resolve("a.txt"), "the account's own");
    Files.writeString(servingDir.resolve("a.txt"), "loose");

    adoption.adopt(owner);

    assertThat(storagePaths.home(owner).root().resolve("a.txt")).hasContent("the account's own");
    assertThat(servingDir.resolve("a.txt")).hasContent("loose");
  }

  @Test
  @DisplayName("before there is any account, nothing moves")
  void nothingMovesWithoutAnAccount() throws IOException {
    Files.writeString(servingDir.resolve("a.txt"), "a");
    when(users.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

    adoption.adoptAtStartup();

    assertThat(servingDir.resolve("a.txt")).exists();
    assertThat(storagePaths.homesDir()).doesNotExist();
  }

  @Test
  @DisplayName("with nothing to move, the search index is left alone")
  void nothingToMove() {
    adoption.adopt(owner);

    verify(searchIndex, never()).changed(any(Home.class), any());
  }
}
