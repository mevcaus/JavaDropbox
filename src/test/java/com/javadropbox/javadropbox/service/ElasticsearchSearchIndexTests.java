package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.ElasticsearchTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
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
  @DisplayName("an index under its name that the app didn't make is left alone, and search is off")
  void foreignIndexIsLeftAlone() throws Exception {
    send("PUT", "/" + alias, "{}");
    send("PUT", "/" + alias + "/_doc/1?refresh=true", "{\"someone\":\"else's\"}");
    Files.writeString(files.resolve("mine.txt"), "mine");

    SearchIndex index = open();
    reconcile(index);

    assertThat(paths(index, "mine")).isEmpty();
    assertThat(index.search(home, "", "mine", 10).complete()).isFalse();
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
  @DisplayName("a change after the index was deleted makes it again rather than letting it be made")
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
  @DisplayName("an index a newer version made is left alone, and search is off")
  void newerFormatIsLeftAlone() throws Exception {
    String newer = alias + "-" + (ElasticsearchSearchIndex.FORMAT + 1);
    send("PUT", "/" + newer, "{}");
    send(
        "POST",
        "/_aliases",
        "{\"actions\":[{\"add\":{\"index\":\"" + newer + "\",\"alias\":\"" + alias + "\"}}]}");

    SearchIndex index = open();
    reconcile(index);

    assertThat(index.search(home, "", "anything", 10).complete()).isFalse();
    assertThat(send("GET", "/" + newer, null)).doesNotContain("index_not_found_exception");
  }

  // Changes never fail because the index can't take them; they wait.
  @Test
  @DisplayName("while the service can't be reached, changes are kept to try again, without failing")
  void unreachableServiceKeepsChanges() throws Exception {
    SearchIndex index =
        new ElasticsearchSearchIndex(items(), "http://127.0.0.1:1", "someone", "secret", alias);
    try {
      index.changed(home, "a.txt");
      index.reconcile();
      Thread.sleep(500);
      assertThat(index.search(home, "", "a", 10).complete()).isFalse();
    } finally {
      close(index);
    }
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
