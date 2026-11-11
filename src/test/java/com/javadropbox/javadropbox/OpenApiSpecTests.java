package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The frontend's API types are generated from frontend/openapi.json (npm run api:types), so that
 * file has to match the spec the controllers describe. A change to an endpoint or a response fails
 * here until the file is regenerated with ./gradlew openApiSpec.
 */
@SpringBootTest(
    properties = {
      "springdoc.api-docs.enabled=true",
      // Sorted and indented, so the file is the same on every run and its diffs are readable.
      "springdoc.writer-with-order-by-keys=true",
      "springdoc.writer-with-default-pretty-printer=true"
    })
@AutoConfigureMockMvc
// The demo's endpoint is in the spec too, so the frontend has its types. Naming the profile also
// keeps a developer's SPRING_PROFILES_ACTIVE from changing the file. The demo stores its sample
// files at startup, hence a serving directory of its own.
@ActiveProfiles("demo")
@TempServingDirectory
@DisplayName("The committed OpenAPI spec")
class OpenApiSpecTests {

  // Set for every test task in build.gradle; the fallback is for IDEs that run tests themselves,
  // from the project's root.
  private static final Path COMMITTED =
      Path.of(System.getProperty("javadropbox.openapi.spec", "frontend/openapi.json"));
  private static final boolean UPDATE = Boolean.getBoolean("javadropbox.openapi.update");

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("matches the spec the controllers describe")
  void matchesTheControllers() throws Exception {
    String live =
        mockMvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8)
                // The pretty printer ends lines the platform's way; the file always uses \n.
                .replace("\r\n", "\n")
            + "\n";

    if (UPDATE) {
      Files.writeString(COMMITTED, live);
      return;
    }
    assertThat(COMMITTED).exists();
    String committed = Files.readString(COMMITTED).replace("\r\n", "\n");
    assertThat(committed.equals(live))
        .as(
            "%s is out of date with the controllers. Run ./gradlew openApiSpec, then npm run"
                + " api:types in frontend/, and commit both files. First difference:%n%s",
            COMMITTED, firstDifference(committed, live))
        .isTrue();
  }

  // A few lines from where the two specs part, rather than both specs in full.
  private static String firstDifference(String committed, String live) {
    List<String> was = committed.lines().toList();
    List<String> is = live.lines().toList();
    int line = 0;
    while (line < was.size() && line < is.size() && was.get(line).equals(is.get(line))) {
      line++;
    }
    return "line %d%n  committed: %s%n  live:      %s"
        .formatted(
            line + 1,
            String.join("\n", was.subList(line, Math.min(line + 3, was.size()))),
            String.join("\n", is.subList(line, Math.min(line + 3, is.size()))));
  }
}
