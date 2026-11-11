package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@link SearchIndexContractTests} on Lucene, and what only the Lucene index does. */
@DisplayName("Lucene search index")
class LuceneSearchIndexTests extends SearchIndexContractTests {

  @TempDir Path indexDir;

  @Override
  SearchIndex create() throws IOException {
    return new LuceneSearchIndex(storagePaths, store, extractor, indexDir.toString());
  }

  @Override
  void close(SearchIndex index) throws Exception {
    ((LuceneSearchIndex) index).close();
  }

  @Test
  @DisplayName("until it has caught up, it searches what was indexed before and says so")
  void searchesTheLastIndexWhileCatchingUp() throws Exception {
    Files.writeString(files.resolve("old.txt"), "remembered");
    SearchIndex first = open();
    reconcile(first);
    closeEarly(first);

    SearchIndex second = open();
    SearchIndex.Hits hits = second.search(home, "", "remembered", 10);
    assertThat(hits.complete()).isFalse();
    assertThat(hits.hits()).extracting(SearchIndex.Hit::path).containsExactly("old.txt");

    reconcile(second);
    assertThat(second.search(home, "", "remembered", 10).complete()).isTrue();
  }

  @Test
  @DisplayName("an index it cannot read is rebuilt")
  void rebuildsAnUnreadableIndex() throws Exception {
    Files.writeString(files.resolve("kept.txt"), "survivor");
    SearchIndex first = open();
    reconcile(first);
    closeEarly(first);
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
    Files.writeString(files.resolve("real.txt"), "ghost story");

    SearchIndex index = open();
    reconcile(index);

    assertThat(paths(index, "ghost")).containsExactly("real.txt");
  }

  @Test
  @DisplayName("while another writer holds the index, changes wait for it rather than being lost")
  void waitsForAnotherWriter() throws Exception {
    SearchIndex index = open();
    reconcile(index);
    Files.writeString(files.resolve("late.txt"), "patience");

    try (FSDirectory directory = FSDirectory.open(indexDir);
        IndexWriter other =
            new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      index.changed(home, "late.txt");
      Thread.sleep(500);
      assertThat(paths(index, "patience")).isEmpty();
    }
    index.awaitIdle(INDEXING);

    assertThat(paths(index, "patience")).containsExactly("late.txt");
  }
}
