package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.service.SearchIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("Search configuration")
class SearchConfigTests {

  @Test
  @DisplayName("lucene and elasticsearch are the backends, in any letter case")
  void knownBackends() {
    assertThatCode(() -> SearchConfig.check("lucene")).doesNotThrowAnyException();
    assertThatCode(() -> SearchConfig.check("Elasticsearch")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a backend the app does not have stops it from starting, saying which it has")
  void unknownBackendIsRefused() {
    assertThatThrownBy(() -> SearchConfig.check("solr"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lucene or elasticsearch")
        .hasMessageContaining("\"solr\"");
  }

  // Before any bean that needs a search index fails for want of one.
  @Test
  @DisplayName("an unknown backend stops the app before anything else, saying which it has")
  void checkedFirst() {
    new ApplicationContextRunner()
        .withUserConfiguration(SearchConfig.class, NeedsAnIndex.class)
        .withPropertyValues(SearchConfig.BACKEND + "=solr")
        .run(
            started ->
                assertThat(started)
                    .getFailure()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lucene or elasticsearch"));
  }

  @Configuration
  static class NeedsAnIndex {
    @Bean
    String needsAnIndex(SearchIndex index) {
      return "never";
    }
  }

  @Test
  @DisplayName("a backend with a space around it is refused, as no index would match it")
  void backendWithSpacesIsRefused() {
    assertThatThrownBy(() -> SearchConfig.check("lucene "))
        .isInstanceOf(IllegalStateException.class);
  }
}
