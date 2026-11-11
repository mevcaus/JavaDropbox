package com.javadropbox.javadropbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javadropbox.javadropbox.dto.AccountDto;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
  @Autowired private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

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

  @Test
  @DisplayName("has every response field required, and null only where it is marked @Nullable")
  void fieldsAreRequiredAndNullOnlyWhereMarked() throws Exception {
    JsonNode schemas = liveSpec().path("components").path("schemas");

    // A record: id and quotaBytes are both Long, but only quotaBytes is marked.
    JsonNode account = schemas.path("AccountDto");
    assertThat(names(account.path("required"))).contains("id", "quotaBytes", "username");
    assertThat(isOrNull(account.path("properties").path("id"))).isFalse();
    assertThat(isOrNull(account.path("properties").path("quotaBytes"))).isTrue();

    // A class with getters: the mark is on the getter's return type.
    JsonNode node = schemas.path("FileTreeNode");
    assertThat(names(node.path("required"))).contains("id", "name", "children");
    assertThat(isOrNull(node.path("properties").path("id"))).isTrue();
    assertThat(isOrNull(node.path("properties").path("name"))).isFalse();
    assertThat(isOrNull(node.path("properties").path("children"))).isFalse();

    // A field that refers back to its own type, which swagger-core drops on its own.
    JsonNode children = schemas.path("SharedEntry").path("properties").path("children");
    assertThat(isOrNull(children)).isTrue();
    assertThat(children.toString()).contains("#/components/schemas/SharedEntry");
    assertThat(names(schemas.path("SharedEntry").path("required"))).contains("children");

    // No schema is left with a field a client would have to treat as maybe missing.
    List<String> optional = new ArrayList<>();
    schemas
        .properties()
        .forEach(
            schema ->
                schema
                    .getValue()
                    .path("properties")
                    .fieldNames()
                    .forEachRemaining(
                        field -> {
                          if (!names(schema.getValue().path("required")).contains(field)) {
                            optional.add(schema.getKey() + "." + field);
                          }
                        }));
    assertThat(optional).isEmpty();
  }

  // Required means always there, which holds only while Jackson writes null fields too.
  @Test
  @DisplayName("is built on responses that carry their null fields")
  void responsesCarryNullFields() throws Exception {
    String json =
        objectMapper.writeValueAsString(new AccountDto(1L, "someone", "USER", true, null, 0));

    assertThat(json).contains("\"quotaBytes\":null");
  }

  // Every field of a schema is required, so a client would have to send all of them.
  @Test
  @DisplayName("uses none of its schemas for a request body")
  void noSchemaIsARequestBody() throws Exception {
    List<String> bodies = new ArrayList<>();
    liveSpec()
        .path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        operation -> {
                          JsonNode body = operation.getValue().path("requestBody");
                          if (body.toString().contains("#/components/schemas/")) {
                            bodies.add(operation.getKey() + " " + path.getKey());
                          }
                        }));

    assertThat(bodies)
        .as("ResponseFieldsConverter marks every field required, which is wrong for a request")
        .isEmpty();
  }

  // springdoc names an operation after its method, suffixing _1 on a clash, so a renamed method or
  // a new endpoint would rename the hooks a client generated from the spec.
  @Test
  @DisplayName("gives every endpoint an id it sets itself, none repeated")
  void everyOperationHasItsOwnId() throws Exception {
    List<String> unnamed = new ArrayList<>();
    Map<String, List<String>> endpointsById = new TreeMap<>();
    handlerMapping
        .getHandlerMethods()
        .forEach(
            (mapping, method) -> {
              Class<?> controller = method.getBeanType();
              Operation operation = method.getMethodAnnotation(Operation.class);
              boolean inSpec =
                  controller.getPackageName().startsWith("com.javadropbox")
                      && !controller.isAnnotationPresent(Hidden.class)
                      && !method.hasMethodAnnotation(Hidden.class)
                      && (operation == null || !operation.hidden());
              if (!inSpec) {
                return;
              }
              String endpoint = controller.getSimpleName() + "." + method.getMethod().getName();
              if (operation == null || operation.operationId().isBlank()) {
                unnamed.add(endpoint);
              } else {
                endpointsById
                    .computeIfAbsent(operation.operationId(), id -> new ArrayList<>())
                    .add(endpoint);
              }
            });
    assertThat(unnamed).as("endpoints without @Operation(operationId = ...)").isEmpty();
    // springdoc would quietly make the second of two equal ids id_1.
    assertThat(endpointsById)
        .as("ids set on more than one endpoint")
        .allSatisfy((id, endpoints) -> assertThat(endpoints).as(id).hasSize(1));

    // And nothing else in the spec, such as one method mapped to two paths, got a number.
    List<String> ids = new ArrayList<>();
    liveSpec()
        .path("paths")
        .forEach(
            path -> path.forEach(operation -> ids.add(operation.path("operationId").asText())));
    assertThat(ids).noneMatch(id -> id.matches(".*_\\d+$"));
  }

  // A map's keys aren't in the spec, so renaming one would change nothing the drift check sees. Nor
  // would changing anything inside an untyped object, such as a field or a response of type Object.
  @Test
  @DisplayName("describes every field of a successful response, with no map or untyped object")
  void noResponseHasAnUntypedPart() throws Exception {
    JsonNode spec = liveSpec();
    List<String> untyped = new ArrayList<>();
    spec.path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        operation ->
                            operation.getValue().path("responses").properties().stream()
                                .filter(response -> response.getKey().startsWith("2"))
                                .forEach(
                                    response ->
                                        response
                                            .getValue()
                                            .path("content")
                                            .forEach(
                                                content ->
                                                    findUntyped(
                                                        content.path("schema"),
                                                        operation.getKey().toUpperCase()
                                                            + " "
                                                            + path.getKey(),
                                                        untyped)))));
    // Every schema in components is a response's (noSchemaIsARequestBody), so this covers the
    // records the responses refer to, and the records inside those.
    spec.path("components")
        .path("schemas")
        .properties()
        .forEach(schema -> findUntyped(schema.getValue(), schema.getKey(), untyped));

    assertThat(untyped).as("maps and untyped objects in responses").isEmpty();
  }

  private static void findUntyped(JsonNode schema, String where, List<String> untyped) {
    if (schema.has("additionalProperties")) {
      untyped.add(where + " is a map");
    }
    boolean object =
        schema.path("type").asText().equals("object")
            || schema.path("type").toString().contains("\"object\"");
    if (object && !schema.has("properties") && !schema.has("additionalProperties")) {
      untyped.add(where + " is an object with no fields");
    }
    schema
        .path("properties")
        .properties()
        .forEach(field -> findUntyped(field.getValue(), where + "." + field.getKey(), untyped));
    for (String nested : List.of("items", "anyOf", "oneOf", "allOf")) {
      JsonNode node = schema.path(nested);
      if (node.isArray()) {
        node.forEach(option -> findUntyped(option, where, untyped));
      } else if (!node.isMissingNode()) {
        findUntyped(node, where + "[]", untyped);
      }
    }
  }

  // Spring reads a @RequestParam from the query string and a form alike; the spec has to say which
  // one the endpoint wants, or a generated client puts passwords in URLs (FormRequestBodies).
  @Test
  @DisplayName(
      "takes form and multipart fields as a named body, and no change takes a field in its URL")
  void formsAreBodies() throws Exception {
    List<String> problems = new ArrayList<>();
    liveSpec()
        .path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        operation -> {
                          String where = operation.getKey().toUpperCase() + " " + path.getKey();
                          JsonNode op = operation.getValue();
                          op.path("requestBody")
                              .path("content")
                              .properties()
                              .forEach(
                                  media -> {
                                    JsonNode schema = media.getValue().path("schema");
                                    if (!schema.has("properties")) {
                                      problems.add(
                                          where
                                              + " has a "
                                              + media.getKey()
                                              + " body with no named fields");
                                    }
                                  });
                          if (!List.of("post", "put", "patch").contains(operation.getKey())) {
                            return;
                          }
                          op.path("parameters")
                              .forEach(
                                  parameter -> {
                                    String name = parameter.path("name").asText();
                                    // The one field a change takes in its URL: how to restore.
                                    boolean allowed =
                                        name.equals("mode") && path.getKey().endsWith("/restore");
                                    if (parameter.path("in").asText().equals("query") && !allowed) {
                                      problems.add(where + " takes " + name + " in the query");
                                    }
                                  });
                        }));

    assertThat(problems).isEmpty();
  }

  private JsonNode liveSpec() throws Exception {
    return new ObjectMapper()
        .readTree(
            mockMvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
  }

  private static List<String> names(JsonNode array) {
    List<String> names = new ArrayList<>();
    array.forEach(name -> names.add(name.asText()));
    return names;
  }

  // A plain field has "null" among its types; a reference is anyOf itself and {"type": "null"}.
  private static boolean isOrNull(JsonNode property) {
    if (property.path("type").toString().contains("\"null\"")) {
      return true;
    }
    for (JsonNode option : property.path("anyOf")) {
      if (option.path("type").toString().contains("\"null\"")) {
        return true;
      }
    }
    return false;
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
