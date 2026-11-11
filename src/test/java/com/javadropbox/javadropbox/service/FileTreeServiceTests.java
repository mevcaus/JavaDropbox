package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.javadropbox.javadropbox.dto.FileTreeNode;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.PreviewType;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("The file tree, built from rows")
class FileTreeServiceTests {

  private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant MAR = Instant.parse("2026-03-01T00:00:00Z");

  private final FileMetadataRepository files = mock(FileMetadataRepository.class);
  private final FileTreeService tree = new FileTreeService(files);
  private final User ada = new User("ada", "unused", User.ROLE_USER);
  private long nextId = 1;

  @BeforeEach
  void setUp() {
    ada.setId(7L);
  }

  @Test
  @DisplayName("rows become a tree: folders first by name, sizes added up, with ids and owners")
  void rowsBecomeATree() {
    when(files.findAllOf(7L))
        .thenReturn(
            List.of(
                file("b.txt", 2, MAR),
                folder("Work", JAN),
                file("Work/report.pdf", 5, JAN),
                folder("Work/2024", JAN),
                file("Work/2024/a.txt", 3, MAR),
                file("A.TXT", 1, JAN)));

    List<FileTreeNode> nodes = tree.tree(ada);

    assertThat(nodes).extracting(FileTreeNode::getName).containsExactly("Work", "A.TXT", "b.txt");
    FileTreeNode work = nodes.get(0);
    assertThat(work.getSize()).isEqualTo(8);
    assertThat(work.getId()).isNotNull();
    assertThat(work.getOwnerName()).isEqualTo("ada");
    assertThat(work.getChildren())
        .extracting(FileTreeNode::getName)
        .containsExactly("2024", "report.pdf");
    assertThat(work.getChildren().get(1).getPreviewType()).isEqualTo(PreviewType.PDF);
    assertThat(work.getChildren().get(1).getRelativePath()).isEqualTo("Work/report.pdf");
    assertThat(work.getChildren().get(0).getChildren())
        .singleElement()
        .extracting(FileTreeNode::getRelativePath)
        .isEqualTo("Work/2024/a.txt");
  }

  @Test
  @DisplayName("a row whose folder has no row of its own still shows, in a folder made up for it")
  void missingFolderRowIsMadeUp() {
    when(files.findAllOf(7L)).thenReturn(List.of(file("Old/Deep/x.txt", 4, MAR)));

    List<FileTreeNode> nodes = tree.tree(ada);

    FileTreeNode old = nodes.get(0);
    assertThat(old.getName()).isEqualTo("Old");
    assertThat(old.getIsDirectory()).isTrue();
    assertThat(old.getId()).isNull();
    assertThat(old.getOwnerName()).isEqualTo("ada");
    assertThat(old.getSize()).isEqualTo(4);
    assertThat(old.getLastModified()).isEqualTo(MAR);
    assertThat(old.getChildren().get(0).getRelativePath()).isEqualTo("Old/Deep");
    assertThat(old.getChildren().get(0).getChildren().get(0).getName()).isEqualTo("x.txt");
  }

  @Test
  @DisplayName("a shared folder lists what is below it, from rows below it only")
  void sharedFolderListsBelowIt(@TempDir Path servingDir) throws IOException {
    LocalFileStore store = new LocalFileStore(servingDir);
    StoragePaths storagePaths = new StoragePaths(store, servingDir);
    when(files.findAllBelow(eq(7L), anyString()))
        .thenReturn(List.of(folder("Work/2024", JAN), file("Work/2024/a.txt", 3, MAR)));

    List<FileTreeNode> nodes =
        tree.tree(new StoragePaths.StoragePath(storagePaths.home(ada), "Work"));

    assertThat(nodes).singleElement().extracting(FileTreeNode::getName).isEqualTo("2024");
    assertThat(nodes.get(0).getChildren())
        .singleElement()
        .extracting(FileTreeNode::getSize)
        .isEqualTo(3L);
  }

  @Test
  @DisplayName("rows an older release left with a hidden name or an empty segment aren't listed")
  void oddRowsAreLeftOut() {
    when(files.findAllOf(7L))
        .thenReturn(
            List.of(
                file(".DS_Store", 1, JAN),
                file(".git/HEAD", 1, JAN),
                file("a//b.txt", 1, JAN),
                file("/lead.txt", 1, JAN),
                file("kept.txt", 1, JAN)));

    assertThat(tree.tree(ada)).extracting(FileTreeNode::getName).containsExactly("kept.txt");
  }

  @Test
  @DisplayName("a file row with rows below it is listed once, as a folder")
  void fileRowWithChildrenIsAFolder() {
    when(files.findAllOf(7L))
        .thenReturn(List.of(file("Odd", 9, JAN), file("Odd/inside.txt", 2, MAR)));

    List<FileTreeNode> nodes = tree.tree(ada);

    assertThat(nodes).singleElement().extracting(FileTreeNode::getIsDirectory).isEqualTo(true);
    assertThat(nodes.get(0).getSize()).isEqualTo(2);
    assertThat(nodes.get(0).getChildren())
        .singleElement()
        .extracting(FileTreeNode::getName)
        .isEqualTo("inside.txt");
  }

  @Test
  @DisplayName("an account with no rows has an empty tree")
  void noRowsNoTree() {
    when(files.findAllOf(anyLong())).thenReturn(List.of());

    assertThat(tree.tree(ada)).isEmpty();
  }

  private FileMetadata file(String path, long size, Instant updated) {
    return row(path, size, false, updated);
  }

  private FileMetadata folder(String path, Instant updated) {
    return row(path, 0, true, updated);
  }

  private FileMetadata row(String path, long size, boolean isDirectory, Instant updated) {
    FileMetadata row =
        new FileMetadata(path, path.substring(path.lastIndexOf('/') + 1), size, isDirectory, ada);
    ReflectionTestUtils.setField(row, "id", nextId++);
    row.setUpdatedAt(updated);
    return row;
  }
}
