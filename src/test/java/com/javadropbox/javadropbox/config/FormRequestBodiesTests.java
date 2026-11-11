package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;

/** The customizer on controllers made for it, with operations shaped as springdoc makes them. */
@DisplayName("FormRequestBodies")
class FormRequestBodiesTests {

  private static final String FORM = "application/x-www-form-urlencoded";

  static class Forms {
    @PostMapping(path = "/aliased", consumes = FORM)
    void aliased(
        @RequestParam("files") String uploads, @RequestParam(required = false) String note) {}

    @PostMapping(path = "/hidden", consumes = FORM)
    void hidden(
        @RequestParam String name,
        @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam String secret) {}

    @PostMapping(path = "/missing", consumes = FORM)
    void missing(@RequestParam String name, @RequestParam String other) {}

    @PostMapping(path = "/parts", consumes = "multipart/form-data")
    void parts(@RequestPart MultipartFile file, @RequestParam String name) {}

    @PostMapping("/query")
    void query(@RequestParam String mode) {}
  }

  @RequestMapping(consumes = FORM)
  static class FormController {
    @PostMapping("/class")
    void onClass(@RequestParam String name) {}
  }

  private static HandlerMethod handler(Object controller, String method) {
    return new HandlerMethod(
        controller,
        java.util.Arrays.stream(controller.getClass().getDeclaredMethods())
            .filter(m -> m.getName().equals(method))
            .findFirst()
            .orElseThrow());
  }

  private static Parameter query(String name) {
    return new Parameter().in("query").name(name).schema(new JsonSchema().types(Set.of("string")));
  }

  private static Operation withQuery(String... names) {
    List<Parameter> parameters = new ArrayList<>();
    for (String name : names) {
      parameters.add(query(name));
    }
    return new Operation().parameters(parameters);
  }

  private static Schema<?> body(Operation operation) {
    return operation.getRequestBody().getContent().get(FORM).getSchema();
  }

  @Test
  @DisplayName("names a field by its @RequestParam alias, and requires only the required ones")
  void readsAliases() {
    Operation operation =
        new FormRequestBodies()
            .customize(withQuery("files", "note"), handler(new Forms(), "aliased"));

    assertThat(body(operation).getProperties()).containsOnlyKeys("files", "note");
    assertThat(body(operation).getRequired()).containsExactly("files");
    assertThat(operation.getRequestBody().getRequired()).isTrue();
    assertThat(operation.getParameters()).isNull();
  }

  @Test
  @DisplayName("leaves a hidden field out, as springdoc does")
  void skipsHiddenFields() {
    Operation operation =
        new FormRequestBodies().customize(withQuery("name"), handler(new Forms(), "hidden"));

    assertThat(body(operation).getProperties()).containsOnlyKeys("name");
  }

  @Test
  @DisplayName("takes consumes from the controller when the method declares none")
  void readsTheControllersConsumes() {
    Operation operation =
        new FormRequestBodies()
            .customize(withQuery("name"), handler(new FormController(), "onClass"));

    assertThat(body(operation).getProperties()).containsOnlyKeys("name");
  }

  @Test
  @DisplayName("names a one-field form springdoc collapsed to that field's bare schema")
  void namesACollapsedField() {
    Schema<?> bare = new JsonSchema().types(Set.of("string"));
    Operation collapsed =
        new Operation()
            .requestBody(
                new RequestBody()
                    .content(new Content().addMediaType(FORM, new MediaType().schema(bare))));

    Operation operation =
        new FormRequestBodies().customize(collapsed, handler(new FormController(), "onClass"));

    assertThat(body(operation).getProperties()).isEqualTo(Map.of("name", bare));
  }

  @Test
  @DisplayName("leaves an endpoint that takes no form alone")
  void leavesQueriesAlone() {
    Operation operation =
        new FormRequestBodies().customize(withQuery("mode"), handler(new Forms(), "query"));

    assertThat(operation.getRequestBody()).isNull();
    assertThat(operation.getParameters()).extracting(Parameter::getName).containsExactly("mode");
  }

  @Test
  @DisplayName("refuses a field springdoc left out, rather than leaving it in the query")
  void refusesMissingFields() {
    // A two-field form whose body springdoc collapsed: the bare schema can't say which it is.
    Operation collapsed =
        new Operation()
            .requestBody(
                new RequestBody()
                    .content(
                        new Content()
                            .addMediaType(
                                FORM,
                                new MediaType().schema(new JsonSchema().types(Set.of("string"))))));

    assertThatThrownBy(
            () -> new FormRequestBodies().customize(collapsed, handler(new Forms(), "missing")))
        .hasMessageContaining("Forms.missing")
        .hasMessageContaining("\"name\"");
  }

  @Test
  @DisplayName("refuses a form mixed with @RequestPart, whose part it would drop")
  void refusesRequestParts() {
    assertThatThrownBy(
            () ->
                new FormRequestBodies().customize(withQuery("name"), handler(new Forms(), "parts")))
        .hasMessageContaining("Forms.parts")
        .hasMessageContaining("@RequestPart");
  }
}
