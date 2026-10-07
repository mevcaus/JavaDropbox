package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Search index")
class SearchIndexTests {

  private static final Duration INDEXING = Duration.ofSeconds(30);

  @TempDir Path servingDir;
  @TempDir Path indexDir;

  private StoragePaths storagePaths;
  private final AtomicInteger extracted = new AtomicInteger();
  private final List<SearchIndex> opened = new ArrayList<>();

  // Counts the files whose text is read.
  private final TextExtractor extractor =
      new TextExtractor("50MB") {
        @Override
        public Optional<String> extract(Path file, String name, long size) throws IOException {
          extracted.incrementAndGet();
          return super.extract(file, name, size);
        }
      };

  @BeforeEach
  void setUp() throws IOException {
    storagePaths = new StoragePaths(servingDir);
  }

  @AfterEach
  void tearDown() throws Exception {
    for (SearchIndex index : opened) {
      index.close();
    }
  }

  @Test
  @DisplayName("a reconcile reads again only the files whose size or modification time changed")
  void reconcileReadsOnlyWhatChanged() throws Exception {
    Path a = Files.writeString(servingDir.resolve("a.txt"), "alpha");
    Files.writeString(servingDir.resolve("b.txt"), "beta");
    SearchIndex index = open();
    reconcile(index);
    assertThat(extracted).hasValue(2);

    reconcile(index);
    assertThat(extracted).hasValue(2);

    Files.writeString(a, "alpha, changed");
    Files.setLastModifiedTime(a, FileTime.from(Instant.now().plusSeconds(60)));
    reconcile(index);
    assertThat(extracted).hasValue(3);
    assertThat(paths(index, "changed")).containsExactly("a.txt");
  }

  @Test
  @DisplayName("until it has caught up, it searches what was indexed before and says so")
  void searchesTheLastIndexWhileCatchingUp() throws Exception {
    Files.writeString(servingDir.resolve("old.txt"), "remembered");
    SearchIndex first = open();
    reconcile(first);
    first.close();
    opened.remove(first);

    SearchIndex second = open();
    SearchIndex.Hits hits = second.search("", "remembered", 10);
    assertThat(hits.complete()).isFalse();
    assertThat(hits.hits()).extracting(SearchIndex.Hit::path).containsExactly("old.txt");

    reconcile(second);
    assertThat(second.search("", "remembered", 10).complete()).isTrue();
  }

  @Test
  @DisplayName("an index it cannot read is rebuilt")
  void rebuildsAnUnreadableIndex() throws Exception {
    Files.writeString(servingDir.resolve("kept.txt"), "survivor");
    SearchIndex first = open();
    reconcile(first);
    first.close();
    opened.remove(first);
    // Every file of the index, as a disk fault or a bad copy might leave it. The lock file is
    // only a lock, empty whatever happens to the rest.
    try (Stream<Path> files = Files.list(indexDir)) {
      for (Path file : files.toList()) {
        if (!file.getFileName().toString().equals(IndexWriter.WRITE_LOCK_NAME)) {
          Files.writeString(file, "garbage");
        }
      }
    }

    SearchIndex second = open();
    reconcile(second);

    assertThat(paths(second, "survivor")).containsExactly("kept.txt");
  }

  @Test
  @DisplayName("an index another version wrote is rebuilt rather than searched")
  void rebuildsAnIndexOfAnotherFormat() throws Exception {
    try (FSDirectory directory = FSDirectory.open(indexDir);
        IndexWriter writer =
            new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document ghost = new Document();
      ghost.add(new StringField("path", "ghost.txt", Field.Store.YES));
      ghost.add(new StringField("name", "ghost.txt", Field.Store.NO));
      writer.addDocument(ghost);
    }
    Files.writeString(servingDir.resolve("real.txt"), "ghost story");

    SearchIndex index = open();
    reconcile(index);

    assertThat(paths(index, "ghost")).containsExactly("real.txt");
  }

  @Test
  @DisplayName("wildcards typed into a search match only themselves")
  void wildcardsAreLiteral() throws Exception {
    Files.writeString(servingDir.resolve("a*b.txt"), "");
    Files.writeString(servingDir.resolve("c?d.txt"), "");
    Files.writeString(servingDir.resolve("plain.txt"), "");
    SearchIndex index = open();
    reconcile(index);

    assertThat(paths(index, "*")).containsExactly("a*b.txt");
    assertThat(paths(index, "?")).containsExactly("c?d.txt");
  }

  @Test
  @DisplayName("while another writer holds the index, changes wait for it rather than being lost")
  void waitsForAnotherWriter() throws Exception {
    SearchIndex index = open();
    reconcile(index);
    Files.writeString(servingDir.resolve("late.txt"), "patience");

    try (FSDirectory directory = FSDirectory.open(indexDir);
        IndexWriter other =
            new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      index.changed("late.txt");
      Thread.sleep(500);
      assertThat(paths(index, "patience")).isEmpty();
    }
    index.awaitIdle(INDEXING);

    assertThat(paths(index, "patience")).containsExactly("late.txt");
  }

  private SearchIndex open() throws IOException {
    SearchIndex index = new SearchIndex(storagePaths, extractor, indexDir.toString());
    opened.add(index);
    return index;
  }

  private static void reconcile(SearchIndex index) throws InterruptedException {
    index.reconcile();
    index.awaitIdle(INDEXING);
  }

  private static List<String> paths(SearchIndex index, String text) throws IOException {
    return index.search("", text, 10).hits().stream().map(SearchIndex.Hit::path).toList();
  }
}
