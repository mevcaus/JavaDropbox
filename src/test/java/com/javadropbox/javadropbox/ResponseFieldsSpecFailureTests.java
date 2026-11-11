package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * springdoc swallows an exception thrown while a schema is resolved and leaves the response out of
 * the spec. ResponseFieldsConverter's problems have to fail the spec instead, or regenerating it
 * would quietly commit the gap.
 */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@Import(ResponseFieldsSpecFailureTests.Unmarked.class)
@DisplayName("A response the API spec can't describe")
class ResponseFieldsSpecFailureTests {

  /** Not @NullMarked, so whether its field can be null is unknown. */
  record Unknown(Long id) {}

  @RestController
  static class Unmarked {
    @GetMapping("/api/test-only/unknown")
    Unknown unknown() {
      return new Unknown(1L);
    }
  }

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("fails the spec, saying which type and what to do")
  void failsTheSpec() {
    assertThatThrownBy(() -> mockMvc.perform(get("/v3/api-docs")))
        .rootCause()
        .hasMessageContaining(Unknown.class.getName())
        .hasMessageContaining("@NullMarked");
  }
}
