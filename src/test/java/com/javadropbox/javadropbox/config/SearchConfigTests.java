package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Search configuration")
class SearchConfigTests {

  @Test
  @DisplayName("lucene and elasticsearch are the backends, in any letter case")
  void knownBackends() {
    assertThatCode(() -> new SearchConfig("lucene")).doesNotThrowAnyException();
    assertThatCode(() -> new SearchConfig("Elasticsearch")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a backend the app does not have stops it from starting, saying which it has")
  void unknownBackendIsRefused() {
    assertThatThrownBy(() -> new SearchConfig("solr"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lucene or elasticsearch")
        .hasMessageContaining("\"solr\"");
  }

  @Test
  @DisplayName("a backend with a space around it is refused, as no index would match it")
  void backendWithSpacesIsRefused() {
    assertThatThrownBy(() -> new SearchConfig("lucene ")).isInstanceOf(IllegalStateException.class);
  }
}
