package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.model.FileVersion;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Where file and version content is")
class FileContentsTests {

  private static final String HASH =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @TempDir Path serving;

  private LocalFileStore store;
  private Home home;
  // What the lookup was asked: account and path.
  private final List<String> asked = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    store = new LocalFileStore(serving);
    home = new StoragePaths(store, serving).home(7);
    write(home.key() + "/docs/a.txt", "at its path");
    write(".blobs/ba/" + HASH, "in its blob");
  }

  // Rows where docs/a.txt names the blob, and nothing else does.
  private FileContents contents() {
    return new FileContents(
        store,
        (ownerId, path) -> {
          asked.add(ownerId + ":" + path);
          return path.equals("docs/a.txt") ? Optional.of(HASH) : Optional.empty();
        });
  }

  @Test
  @DisplayName("a file whose row names a blob is read from the blob, looked up by its own account")
  void rowNamingABlobReadsIt() throws IOException {
    Entry found = contents().of(home.resolveItem("docs/a.txt")).orElseThrow();

    assertThat(read(found)).isEqualTo("in its blob");
    assertThat(asked).containsExactly("7:docs/a.txt");
  }

  @Test
  @DisplayName("a file whose row names no blob, or that has no row, is read from its path")
  void rowNamingNoBlobReadsThePath() throws IOException {
    write(home.key() + "/docs/b.txt", "b at its path");

    assertThat(read(contents().of(home.resolveItem("docs/b.txt")).orElseThrow()))
        .isEqualTo("b at its path");
    assertThat(contents().of(home.resolveItem("docs")).orElseThrow().isDirectory()).isTrue();
  }

  @Test
  @DisplayName("the account's root is its folder, with no row to look up")
  void rootNeedsNoLookup() throws IOException {
    assertThat(contents().of(home.resolve("")).orElseThrow().isDirectory()).isTrue();
    assertThat(asked).isEmpty();
  }

  @Test
  @DisplayName("a blob that has gone is nothing, not whatever is at the path")
  void missingBlobIsNothing() throws IOException {
    store.delete(".blobs/ba/" + HASH);

    assertThat(contents().of(home.resolveItem("docs/a.txt"))).isEmpty();
  }

  @Test
  @DisplayName("a version is read from the blob its row names, or else below the version store")
  void versionReadsItsBlobOrItsPath() throws IOException {
    write(".versions/12/v1", "kept as a version");
    FileVersion atPath = version("12/v1", null);
    FileVersion inBlob = version("12/v2", HASH);

    assertThat(read(contents().ofVersion(atPath).orElseThrow())).isEqualTo("kept as a version");
    assertThat(read(contents().ofVersion(inBlob).orElseThrow())).isEqualTo("in its blob");
  }

  @Test
  @DisplayName("a version's stored copy is below the version store, by its recorded name")
  void versionKey() {
    assertThat(FileContents.versionKey("12/v3")).isEqualTo(".versions/12/v3");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "../12/v3", "12/../../x", "12//v3", "12/./v3", "/12/v3", "12/v3/"})
  @DisplayName("a recorded name that would lead outside the version store is refused")
  void versionKeyStaysInTheVersionStore(String name) {
    assertThatThrownBy(() -> FileContents.versionKey(name))
        .isInstanceOf(IllegalStateException.class);
  }

  // A version row as the database would load it; its blob is only ever set there.
  private static FileVersion version(String storedFilename, String blobHash) {
    FileVersion version = new FileVersion(null, 1, storedFilename, 0L, null, null);
    ReflectionTestUtils.setField(version, "blobHash", blobHash);
    return version;
  }

  private void write(String key, String content) throws IOException {
    store.createFolders(key.substring(0, key.lastIndexOf('/')));
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    store.write(key, new ByteArrayInputStream(bytes), bytes.length);
  }

  private String read(Entry entry) throws IOException {
    try (InputStream in = contents().open(entry)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
