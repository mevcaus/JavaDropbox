package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The converter on types made for it, so a change to a real response can't break these. */
@DisplayName("ResponseFieldsConverter")
class ResponseFieldsConverterTests {

  @NullMarked
  record Plain(Long id, @Nullable Long quota, int count) {}

  @NullMarked
  record Inner(String text) {}

  @NullMarked
  record Holder(@Nullable Inner inner, Outer.@Nullable Kind kind, List<Inner> items) {}

  @NullMarked
  record Outer(@Nullable List<Outer> children) {
    enum Kind {
      A,
      B
    }
  }

  @NullMarked
  record Top(List<Outer> outers) {}

  @NullMarked
  static class Bean {
    public String getName() {
      return "";
    }

    public @Nullable Long getId() {
      return null;
    }
  }

  @NullMarked
  record Sometimes(String id, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String note) {}

  @NullMarked
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SometimesAll(String id, @Nullable String note) {}

  @NullMarked
  record Hiding(String shown, @Schema(hidden = true) String hidden) {}

  @NullMarked
  record Declared(String a) {}

  @NullMarked
  record Described(String b) {}

  @NullMarked
  record WithImplementation(@Schema(implementation = Described.class) Declared field) {}

  record Unmarked(Long id) {}

  @NullMarked
  record SpringMarked(@org.springframework.lang.Nullable Long id) {}

  @NullMarked
  static class Named {
    @NullMarked
    @Schema(name = "Same")
    record One(String a) {}

    @NullMarked
    @Schema(name = "Same")
    record Two(String b) {}

    @NullMarked
    record Both(One one, Two two) {}
  }

  // Two nested records that swagger-core would both call Entry, and merge without a word.
  @NullMarked
  static class First {
    @NullMarked
    record Entry(String a) {}
  }

  @NullMarked
  static class Second {
    @NullMarked
    record Entry(String b) {}
  }

  @NullMarked
  record BothEntries(First.Entry first, Second.Entry second) {}

  private final ResponseFieldsConverter converter = new ResponseFieldsConverter();

  private Map<String, io.swagger.v3.oas.models.media.Schema> read(Class<?> type) {
    ModelConverters converters = new ModelConverters(true);
    converters.addConverter(converter);
    return converters.readAll(type);
  }

  // What springdoc does once the schemas are resolved, where the converter reports its problems.
  private void finishSpec() {
    converter.customise(new OpenAPI());
  }

  @Test
  @DisplayName("requires every field, and adds null only to a marked one, keeping its format")
  void requiresEveryFieldAndNullsOnlyMarkedOnes() {
    io.swagger.v3.oas.models.media.Schema<?> plain = read(Plain.class).get("Plain");

    assertThat(plain.getRequired()).containsExactlyInAnyOrder("id", "quota", "count");
    Map<String, io.swagger.v3.oas.models.media.Schema> properties = plain.getProperties();
    assertThat(properties.get("id").getTypes()).containsExactly("integer");
    assertThat(properties.get("quota").getTypes()).containsExactly("integer", "null");
    assertThat(properties.get("quota").getFormat()).isEqualTo("int64");
    assertThatCode(this::finishSpec).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("reads the mark from a getter's return type")
  void readsGetters() {
    io.swagger.v3.oas.models.media.Schema<?> bean = read(Bean.class).get("Bean");

    assertThat(bean.getRequired()).containsExactlyInAnyOrder("name", "id");
    assertThat(bean.getProperties().get("name").getTypes()).containsExactly("string");
    assertThat(bean.getProperties().get("id").getTypes()).containsExactly("integer", "null");
  }

  @Test
  @DisplayName("makes a marked reference one of itself or null, and a marked enum take null")
  void nullsReferencesAndEnums() {
    io.swagger.v3.oas.models.media.Schema<?> holder = read(Holder.class).get("Holder");
    Map<String, io.swagger.v3.oas.models.media.Schema> properties = holder.getProperties();

    io.swagger.v3.oas.models.media.Schema<?> inner = properties.get("inner");
    assertThat(inner.getAnyOf()).hasSize(2);
    assertThat(inner.getAnyOf().get(0).get$ref()).endsWith("/Inner");
    assertThat(inner.getAnyOf().get(1).getTypes()).containsExactly("null");

    io.swagger.v3.oas.models.media.Schema<?> kind = properties.get("kind");
    assertThat(kind.getTypes()).contains("null");
    assertThat(new ArrayList<Object>(kind.getEnum())).containsExactly("A", "B", null);

    assertThat(properties.get("items").getTypes()).containsExactly("array");
  }

  @Test
  @DisplayName("puts back a field that refers to its own type, met first inside another type")
  void putsBackSelfReference() {
    io.swagger.v3.oas.models.media.Schema<?> outer = read(Top.class).get("Outer");

    io.swagger.v3.oas.models.media.Schema<?> children = outer.getProperties().get("children");
    assertThat(outer.getRequired()).contains("children");
    assertThat(children.getTypes()).containsExactly("array", "null");
    assertThat(children.getItems().get$ref()).endsWith("/Outer");
  }

  @Test
  @DisplayName("leaves a hidden field out")
  void leavesHiddenFieldsOut() {
    io.swagger.v3.oas.models.media.Schema<?> hiding = read(Hiding.class).get("Hiding");

    assertThat(hiding.getProperties()).containsOnlyKeys("shown");
    assertThat(hiding.getRequired()).containsExactly("shown");
  }

  @Test
  @DisplayName(
      "does not require a field Jackson may leave out, whether the field or its class says so")
  void doesNotRequireWhatJacksonMayOmit() {
    assertThat(read(Sometimes.class).get("Sometimes").getRequired()).containsExactly("id");
    assertThat(read(SometimesAll.class).get("SometimesAll").getRequired()).isNull();
  }

  @Test
  @DisplayName("describes a field declared as one type and described as another by the other")
  void followsSchemaImplementation() {
    Map<String, io.swagger.v3.oas.models.media.Schema> models = read(WithImplementation.class);

    assertThat(models.get("Described").getRequired()).containsExactly("b");
    assertThatCode(this::finishSpec).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("gives the same schema when a type is read again")
  void isTheSameReadTwice() {
    String first = read(Holder.class).get("Holder").toString();
    String second = read(Holder.class).get("Holder").toString();

    assertThat(second).isEqualTo(first);
    assertThatCode(this::finishSpec).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a type that is not @NullMarked, once the spec is finished")
  void refusesUnmarkedTypes() {
    read(Unmarked.class);

    assertThatThrownBy(this::finishSpec)
        .hasMessageContaining(Unmarked.class.getName())
        .hasMessageContaining("@NullMarked");
  }

  @Test
  @DisplayName("refuses a @Nullable other than JSpecify's, which it would otherwise ignore")
  void refusesOtherNullables() {
    read(SpringMarked.class);

    assertThatThrownBy(this::finishSpec)
        .hasMessageContaining("SpringMarked.id")
        .hasMessageContaining("org.springframework.lang.Nullable");
  }

  @Test
  @DisplayName("refuses two types with one schema name, given or derived")
  void refusesNameCollisions() {
    read(Named.Both.class);
    assertThatThrownBy(this::finishSpec).hasMessageContaining("\"Same\"");

    read(BothEntries.class);
    assertThatThrownBy(this::finishSpec)
        .hasMessageContaining("\"Entry\"")
        .hasMessageContaining(First.Entry.class.getName())
        .hasMessageContaining(Second.Entry.class.getName());
  }

  @Test
  @DisplayName("reports a problem once, then starts the next spec clean")
  void reportsOnce() {
    read(Unmarked.class);
    assertThatThrownBy(this::finishSpec).isInstanceOf(IllegalStateException.class);

    assertThatCode(this::finishSpec).doesNotThrowAnyException();
  }
}
