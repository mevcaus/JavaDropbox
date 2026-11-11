package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.ElasticsearchTestSupport;
import org.junit.jupiter.api.DisplayName;

/** The {@link SearchIndexContractTests} on Elasticsearch. */
@DisplayName("Elasticsearch search index")
class ElasticsearchSearchIndexTests extends SearchIndexContractTests {

  @Override
  SearchIndex create() {
    return new ElasticsearchSearchIndex(
        items(), ElasticsearchTestSupport.url(), "", "", ElasticsearchTestSupport.newIndex());
  }

  @Override
  void close(SearchIndex index) throws Exception {
    ((ElasticsearchSearchIndex) index).close();
  }
}
