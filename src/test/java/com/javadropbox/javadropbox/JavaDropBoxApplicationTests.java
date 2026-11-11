package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.javadropbox.javadropbox.service.LuceneSearchIndex;
import com.javadropbox.javadropbox.service.SearchIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class JavaDropBoxApplicationTests {

  @Autowired private SearchIndex searchIndex;

  @Test
  void contextLoads() {}

  @Test
  @DisplayName("search is on the Lucene index inside the app unless told otherwise")
  void luceneByDefault() {
    assertThat(searchIndex).isInstanceOf(LuceneSearchIndex.class);
  }
}
