package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.service.ElasticsearchSearchIndex;
import com.javadropbox.javadropbox.service.SearchIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Every search test, on Elasticsearch: it has to find and show what Lucene does. */
@DisplayName("Search on Elasticsearch")
class ElasticsearchSearchIntegrationTests extends SearchIntegrationTests {

  @Autowired private SearchIndex index;

  @DynamicPropertySource
  static void elasticsearch(DynamicPropertyRegistry registry) {
    ElasticsearchTestSupport.register(registry);
  }

  @Test
  @DisplayName("javadropbox.search.backend=elasticsearch puts search on Elasticsearch")
  void usesElasticsearch() {
    assertThat(index).isInstanceOf(ElasticsearchSearchIndex.class);
  }
}
