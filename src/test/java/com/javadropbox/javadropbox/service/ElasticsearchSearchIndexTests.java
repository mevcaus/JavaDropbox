package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.ElasticsearchTestSupport;
import com.javadropbox.javadropbox.TcpProxy;
import com.javadropbox.javadropbox.exception.ServiceUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The {@link SearchIndexContractTests} on Elasticsearch, and what only it does. */
@DisplayName("Elasticsearch search index")
class ElasticsearchSearchIndexTests extends SearchIndexContractTests {

  private final HttpClient http = HttpClient.newHttpClient();
  private final String alias = ElasticsearchTestSupport.newIndex();

  @Override
  SearchIndex create() {
    return open(alias);
  }

  private SearchIndex open(String name) {
    return new ElasticsearchSearchIndex(items(), ElasticsearchTestSupport.url(), "", "", name);
  }

  @Override
  void close(SearchIndex index) throws Exception {
    ((ElasticsearchSearchIndex) index).close();
  }

  // What the epic is for: app servers behind a load balancer, each with an index of its own
  // making, all on one service.
  @Test
  @DisplayName("two servers sharing one index find what each other stored")
  void serversShareTheIndex() throws Exception {
    SearchIndex first = open();
    reconcile(first);
    SearchIndex second = open();
    reconcile(second);

    Files.writeString(files.resolve("from-first.txt"), "shared knowledge");
    first.changed(home, "from-first.txt");
    first.awaitIdle(INDEXING);

    assertThat(paths(second, "knowledge")).containsExactly("from-first.txt");
  }

  @Test
  @DisplayName(
      "an index under its name that the app didn't make is left alone, and searches are refused")
  void foreignIndexIsLeftAlone() throws Exception {
    send("PUT", "/" + alias, "{}");
    send("PUT", "/" + alias + "/_doc/1?refresh=true", "{\"someone\":\"else's\"}");
    Files.writeString(files.resolve("mine.txt"), "mine");

    SearchIndex index = open();
    reconcile(index);

    assertThatThrownBy(() -> index.search(home, "", "mine", 10))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(index.problem()).hasValueSatisfying(p -> assertThat(p).contains("didn't make"));
    assertThat(send("GET", "/" + alias + "/_doc/1", null)).contains("else's");
  }

  @Test
  @DisplayName("an index deleted behind the app's back is made again and filled")
  void deletedIndexIsMadeAgain() throws Exception {
    Files.writeString(files.resolve("kept.txt"), "survivor");
    SearchIndex index = open();
    reconcile(index);
    assertThat(paths(index, "survivor")).containsExactly("kept.txt");

    send("DELETE", "/" + alias + "-" + ElasticsearchSearchIndex.FORMAT, null);
    // The search that finds it gone asks for it to be made again.
    assertThat(index.search(home, "", "survivor", 10).complete()).isFalse();
    index.awaitIdle(INDEXING);

    assertThat(paths(index, "survivor")).containsExactly("kept.txt");
    assertThat(index.search(home, "", "survivor", 10).complete()).isTrue();
  }

  @Test
  @DisplayName("a change after the index was deleted makes it again, with the app's own mapping")
  void writeAfterDeletionMakesItAgain() throws Exception {
    SearchIndex index = open();
    reconcile(index);
    send("DELETE", "/" + alias + "-" + ElasticsearchSearchIndex.FORMAT, null);

    Files.writeString(files.resolve("late.txt"), "afterwards");
    index.changed(home, "late.txt");
    index.awaitIdle(INDEXING);

    assertThat(paths(index, "afterwards")).containsExactly("late.txt");
    // Made by the app, with its own mapping, not by Elasticsearch guessing one.
    assertThat(send("GET", "/" + alias + "/_mapping", null)).contains("javadropbox_text");
  }

  @Test
  @DisplayName("an index an older version made is replaced, and the alias moved to the new one")
  void olderFormatIsReplaced() throws Exception {
    String old = alias + "-0";
    send("PUT", "/" + old, "{}");
    send(
        "POST",
        "/_aliases",
        "{\"actions\":[{\"add\":{\"index\":\"" + old + "\",\"alias\":\"" + alias + "\"}}]}");
    Files.writeString(files.resolve("a.txt"), "upgraded");

    SearchIndex index = open();
    reconcile(index);

    assertThat(paths(index, "upgraded")).containsExactly("a.txt");
    assertThat(send("GET", "/" + old, null)).contains("index_not_found_exception");
  }

  // An older server during a rolling upgrade must not undo what a newer one made.
  @Test
  @DisplayName("an index a newer version made is left alone, and searches are refused")
  void newerFormatIsLeftAlone() throws Exception {
    String newer = alias + "-" + (ElasticsearchSearchIndex.FORMAT + 1);
    send("PUT", "/" + newer, "{}");
    send(
        "POST",
        "/_aliases",
        "{\"actions\":[{\"add\":{\"index\":\"" + newer + "\",\"alias\":\"" + alias + "\"}}]}");

    SearchIndex index = open();
    reconcile(index);

    assertThatThrownBy(() -> index.search(home, "", "anything", 10))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(send("GET", "/" + newer, null)).doesNotContain("index_not_found_exception");
  }

  // Changes never fail because the index can't take them; they wait. A search can't be answered.
  @Test
  @DisplayName(
      "while the service can't be reached, changes are kept to try again, and searches are refused")
  void unreachableServiceKeepsChanges() throws Exception {
    SearchIndex index =
        new ElasticsearchSearchIndex(items(), "http://127.0.0.1:1", "someone", "secret", alias);
    try {
      assertThat(index.problem()).isEmpty();
      index.changed(home, "a.txt");
      // Still waiting to try again, rather than given up.
      assertThatThrownBy(() -> index.awaitIdle(Duration.ofSeconds(2)))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> index.search(home, "", "a", 10))
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessage(ElasticsearchSearchIndex.UNAVAILABLE);
      assertThat(index.problem())
          .hasValueSatisfying(
              problem ->
                  assertThat(problem)
                      .contains("http://127.0.0.1:1", "can't be reached")
                      .doesNotContain("secret"));
    } finally {
      close(index);
    }
  }

  // A service that takes connections but never answers mustn't hold a request for long, nor every
  // one after it: each holds one of the server's threads.
  @Test
  @DisplayName("a service that hangs fails a search in seconds, and the searches after it at once")
  void hangingService() throws Exception {
    try (TcpProxy proxy = new TcpProxy(ElasticsearchTestSupport.url())) {
      Files.writeString(files.resolve("a.txt"), "patience");
      SearchIndex index = new ElasticsearchSearchIndex(items(), proxy.url(), "", "", alias);
      try {
        reconcile(index);
        proxy.hang();

        // The first may fail at once, on a connection the proxy dropped. Once the searches after
        // it have stopped failing at once, the next opens a connection, and gets no answer.
        assertThatThrownBy(() -> index.search(home, "", "patience", 10))
            .isInstanceOf(ServiceUnavailableException.class);
        long started = System.nanoTime();
        assertThatThrownBy(() -> index.search(home, "", "patience", 10))
            .isInstanceOf(ServiceUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        Thread.sleep(ElasticsearchSearchIndex.SEARCH_PAUSE.toMillis());
        started = System.nanoTime();
        assertThatThrownBy(() -> index.search(home, "", "patience", 10))
            .isInstanceOf(ServiceUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .isGreaterThan(Duration.ofSeconds(2))
            .isLessThan(Duration.ofSeconds(15));

        proxy.start();
        Thread.sleep(ElasticsearchSearchIndex.SEARCH_PAUSE.toMillis());
        assertThat(paths(index, "patience")).containsExactly("a.txt");
        assertThat(index.problem()).isEmpty();
      } finally {
        close(index);
      }
    }
  }

  // Each server reconciles when it starts. One finding the index filled by another needn't say it
  // is still being built meanwhile; one finding it not yet filled does, until it has filled it.
  @Test
  @DisplayName("a server finding the index filled by another counts it as complete at once")
  void filledByAnotherServer() throws Exception {
    Files.writeString(files.resolve("a.txt"), "filled");
    reconcile(open());
    // As an index made before servers marked it, or one whose filling was cut short.
    send(
        "PUT", "/" + alias + "-" + ElasticsearchSearchIndex.FORMAT + "/_mapping", "{\"_meta\":{}}");

    SearchIndex second = open();
    second.changed(home, "a.txt");
    second.awaitIdle(INDEXING);
    assertThat(second.search(home, "", "filled", 10).complete()).isFalse();
    reconcile(second);
    assertThat(second.search(home, "", "filled", 10).complete()).isTrue();

    SearchIndex third = open();
    third.changed(home, "a.txt");
    third.awaitIdle(INDEXING);
    SearchIndex.Hits hits = third.search(home, "", "filled", 10);
    assertThat(hits.complete()).isTrue();
    assertThat(hits.hits()).extracting(SearchIndex.Hit::path).containsExactly("a.txt");
  }

  @Test
  @DisplayName("a search the service refuses is answered as unavailable, and logged")
  void refusedSearch() throws Exception {
    SearchIndex index = open();
    reconcile(index);
    // Closed, an index refuses searches with 400.
    send("POST", "/" + alias + "-" + ElasticsearchSearchIndex.FORMAT + "/_close", null);
    try {
      assertThatThrownBy(() -> index.search(home, "", "anything", 10))
          .isInstanceOf(ServiceUnavailableException.class);
      assertThat(index.problem()).hasValueSatisfying(p -> assertThat(p).contains("refused"));
    } finally {
      send("POST", "/" + alias + "-" + ElasticsearchSearchIndex.FORMAT + "/_open", null);
    }
    awaitSearchable(index);
    assertThat(index.problem()).isEmpty();
  }

  // An index just opened again takes a moment to be searchable.
  private void awaitSearchable(SearchIndex index) throws Exception {
    long deadline = System.nanoTime() + INDEXING.toNanos();
    while (true) {
      try {
        index.search(home, "", "anything", 10);
        return;
      } catch (ServiceUnavailableException e) {
        if (System.nanoTime() > deadline) {
          throw e;
        }
        Thread.sleep(100);
      }
    }
  }

  // Stamps are read a page at a time: past the first page, a reconcile must still know what is
  // indexed, or it reads every file again.
  @Test
  @DisplayName("a reconcile past a page of stamps reads again only what changed")
  void reconcilePastAPage() throws Exception {
    for (int i = 0; i <= ElasticsearchSearchIndex.PAGE; i++) {
      Files.writeString(files.resolve("f" + i + ".txt"), "x");
    }
    SearchIndex index = open();
    reconcile(index);
    int read = extracted.get();
    assertThat(read).isGreaterThan(ElasticsearchSearchIndex.PAGE);

    reconcile(index);

    assertThat(extracted).hasValue(read);
  }

  @Test
  @DisplayName("an index name Elasticsearch wouldn't take stops the app from starting")
  void badIndexName() {
    assertThatThrownBy(
            () ->
                new ElasticsearchSearchIndex(
                    items(), ElasticsearchTestSupport.url(), "", "", "Uppercase*"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Uppercase*");
  }

  @Test
  @DisplayName("the URL may have a path and a trailing slash, but no username or password")
  void urls() {
    assertThat(ElasticsearchSearchIndex.parse("http://es:9200/").getHost()).isEqualTo("es");
    assertThat(ElasticsearchSearchIndex.parse("https://proxy/search").getPath())
        .isEqualTo("/search");
    assertThatThrownBy(() -> ElasticsearchSearchIndex.parse("https://user:secret@es:9200"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("username")
        .hasMessageNotContaining("secret");
    assertThatThrownBy(() -> ElasticsearchSearchIndex.parse("es:9200"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("a URL with a trailing slash works")
  void trailingSlash() throws Exception {
    Files.writeString(files.resolve("a.txt"), "slash");
    SearchIndex index =
        new ElasticsearchSearchIndex(items(), ElasticsearchTestSupport.url() + "/", "", "", alias);
    try {
      reconcile(index);
      assertThat(paths(index, "slash")).containsExactly("a.txt");
    } finally {
      close(index);
    }
  }

  private String send(String method, String path, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(ElasticsearchTestSupport.url() + path))
            .header("Content-Type", "application/json");
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
  }
}
