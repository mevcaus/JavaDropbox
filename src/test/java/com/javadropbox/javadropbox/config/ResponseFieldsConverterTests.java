package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The converter on records made for it, so a change to a real response can't break these. */
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
  record Sometimes(@JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String note) {}

  @NullMarked
  record Hiding(String shown, @Schema(hidden = true) String hidden) {}

  record Unmarked(Long id) {}

  @NullMarked
  record SpringMarked(@org.springframework.lang.Nullable Long id) {}

  @NullMarked
  static class Collide {
    @NullMarked
    @Schema(name = "Same")
    record One(String a) {}

    @NullMarked
    @Schema(name = "Same")
    record Two(String b) {}

    @NullMarked
    record Both(One one, Two two) {}
  }

  private static Map<String, io.swagger.v3.oas.models.media.Schema> read(Class<?> type) {
    ModelConverters converters = new ModelConverters(true);
    converters.addConverter(new ResponseFieldsConverter());
    return converters.readAll(type);
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
    assertThat(new java.util.ArrayList<Object>(kind.getEnum())).containsExactly("A", "B", null);

    assertThat(properties.get("items").getTypes()).containsExactly("array");
  }

  @Test
  @DisplayName("puts back a field that refers to its own type, met first inside another type")
  void putsBackSelfReference() {
    io.swagger.v3.oas.models.media.Schema<?> outer = read(Top.class).get("Outer");

    assertThat(outer.getProperties()).containsKey("children");
    assertThat(outer.getRequired()).contains("children");
  }

  @Test
  @DisplayName("leaves a hidden field out")
  void leavesHiddenFieldsOut() {
    io.swagger.v3.oas.models.media.Schema<?> hiding = read(Hiding.class).get("Hiding");

    assertThat(hiding.getProperties()).containsOnlyKeys("shown");
    assertThat(hiding.getRequired()).containsExactly("shown");
  }

  @Test
  @DisplayName("does not require a field Jackson may leave out")
  void doesNotRequireWhatJacksonMayOmit() {
    io.swagger.v3.oas.models.media.Schema<?> sometimes = read(Sometimes.class).get("Sometimes");

    assertThat(sometimes.getRequired()).isNull();
  }

  @Test
  @DisplayName("gives the same schema when a type is read again")
  void isTheSameReadTwice() {
    ModelConverters converters = new ModelConverters(true);
    converters.addConverter(new ResponseFieldsConverter());

    var first = converters.readAll(Holder.class).get("Holder").toString();
    var second = converters.readAll(Holder.class).get("Holder").toString();

    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("refuses a type that is not @NullMarked")
  void refusesUnmarkedTypes() {
    assertThatThrownBy(() -> read(Unmarked.class))
        .hasMessageContaining(Unmarked.class.getName())
        .hasMessageContaining("@NullMarked");
  }

  @Test
  @DisplayName("refuses a @Nullable other than JSpecify's, which it would otherwise ignore")
  void refusesOtherNullables() {
    assertThatThrownBy(() -> read(SpringMarked.class))
        .hasMessageContaining("SpringMarked.id")
        .hasMessageContaining("org.springframework.lang.Nullable");
  }

  @Test
  @DisplayName("refuses two types with one schema name")
  void refusesNameCollisions() {
    assertThatThrownBy(() -> read(Collide.Both.class)).hasMessageContaining("\"Same\"");
  }
}
