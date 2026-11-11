package com.javadropbox.javadropbox.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Says in the API spec which fields of a response are always there and which can be null, so the
 * frontend's generated types are exact.
 *
 * <p>Jackson writes every field, nulls included, so every field of our types is required. One is
 * nullable, {@code T | null} in the generated types, only when its type is marked with JSpecify's
 * {@link Nullable}: on a record component, or on a getter's return type. Unmarked means never null,
 * which is what {@link NullMarked} says, so every type in the spec has to be in a {@code
 * NullMarked} class or package. Building the spec fails, and with it the build, when one isn't,
 * when a field is marked with some other {@code @Nullable} that would be ignored, or when two types
 * would share a schema name.
 *
 * <p>It also puts back a field that refers to its own type, which swagger-core leaves out when it
 * first meets that type inside another one.
 *
 * <p>Every schema in the spec is a response; OpenApiSpecTests fails if one is used as a request
 * body, which would need its own rules, since a client need not send every field.
 */
@Component
public class ResponseFieldsConverter implements ModelConverter {

  private static final ObjectMapper JACKSON = new ObjectMapper();

  // The type each schema name stands for.
  private final Map<String, Class<?>> names = new ConcurrentHashMap<>();

  @Override
  public @Nullable Schema<?> resolve(
      AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    if (!chain.hasNext()) {
      return null;
    }
    Schema<?> resolved = chain.next().resolve(type, context, chain);
    if (resolved == null || type.getType() == null) {
      return resolved;
    }
    JavaType javaType = JACKSON.constructType(type.getType());
    Class<?> raw = javaType.getRawClass();
    if (!raw.getPackageName().startsWith("com.javadropbox")) {
      return resolved;
    }
    String name = name(resolved);
    Schema<?> model = name == null ? resolved : context.getDefinedModels().get(name);
    // Going by Jackson's fields rather than the schema's: swagger-core can leave a type with none,
    // when its only field refers back to it.
    List<BeanPropertyDefinition> fields =
        JACKSON.getSerializationConfig().introspect(javaType).findProperties();
    if (model == null || raw.isEnum() || fields.isEmpty()) {
      return resolved;
    }
    if (name != null) {
      Class<?> other = names.putIfAbsent(name, raw);
      if (other != null && other != raw) {
        throw new IllegalStateException(
            "%s and %s would both be \"%s\" in the API spec. Give one a @Schema(name = ...)."
                .formatted(other.getName(), raw.getName(), name));
      }
    }
    if (!isNullMarked(raw)) {
      throw new IllegalStateException(
          ("%s is in the API spec but not @NullMarked, so whether its fields can be null is"
                  + " unknown. Mark it or its package @NullMarked, and the fields that can be null"
                  + " @Nullable.")
              .formatted(raw.getName()));
    }

    for (BeanPropertyDefinition property : fields) {
      String field = property.getName();
      Schema<?> schema = model.getProperties() == null ? null : model.getProperties().get(field);
      if (schema == null) {
        if (!refersTo(property, raw)) {
          continue;
        }
        schema = context.resolve(new AnnotatedType(property.getPrimaryType()).resolveAsRef(true));
        if (schema == null) {
          continue;
        }
        model.addProperty(field, schema);
      }
      // A field Jackson may leave out can't be promised to be there.
      if (isAlwaysWritten(property)
          && (model.getRequired() == null || !model.getRequired().contains(field))) {
        model.addRequiredItem(field);
      }
      if (isNullable(property, raw)) {
        model.getProperties().put(field, orNull(schema));
      }
    }
    return resolved;
  }

  // The resolver hands back either the model, named, or a reference to it.
  private static @Nullable String name(Schema<?> resolved) {
    String ref = resolved.get$ref();
    if (ref != null) {
      return ref.substring(ref.lastIndexOf('/') + 1);
    }
    return resolved.getName();
  }

  private static boolean isNullMarked(Class<?> type) {
    for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
      if (c.isAnnotationPresent(NullMarked.class)) {
        return true;
      }
    }
    return type.getPackage() != null && type.getPackage().isAnnotationPresent(NullMarked.class);
  }

  // A field of type T, or a list of T, where T is the type being described.
  private static boolean refersTo(BeanPropertyDefinition property, Class<?> type) {
    JavaType fieldType = property.getPrimaryType();
    return fieldType.getRawClass() == type
        || (fieldType.getContentType() != null && fieldType.getContentType().getRawClass() == type);
  }

  private static boolean isAlwaysWritten(BeanPropertyDefinition property) {
    JsonInclude.Include inclusion = property.findInclusion().getValueInclusion();
    return inclusion == JsonInclude.Include.ALWAYS || inclusion == JsonInclude.Include.USE_DEFAULTS;
  }

  // A record component's annotations are on its field and its accessor alike.
  private static boolean isNullable(BeanPropertyDefinition property, Class<?> owner) {
    List<Annotation> annotations = new ArrayList<>();
    AnnotatedMethod getter = property.getGetter();
    if (getter != null) {
      annotations.addAll(List.of(getter.getAnnotated().getAnnotatedReturnType().getAnnotations()));
      annotations.addAll(List.of(((AnnotatedElement) getter.getAnnotated()).getAnnotations()));
    }
    AnnotatedField field = property.getField();
    if (field != null) {
      annotations.addAll(List.of(field.getAnnotated().getAnnotatedType().getAnnotations()));
      annotations.addAll(List.of(field.getAnnotated().getAnnotations()));
    }
    Stream<Class<? extends Annotation>> others =
        annotations.stream()
            .map(Annotation::annotationType)
            .filter(a -> a.getSimpleName().equals("Nullable") && a != Nullable.class);
    others
        .findFirst()
        .ifPresent(
            other -> {
              throw new IllegalStateException(
                  ("%s.%s is marked with %s, which the API spec ignores. Use %s.")
                      .formatted(
                          owner.getName(),
                          property.getName(),
                          other.getName(),
                          Nullable.class.getName()));
            });
    return annotations.stream().anyMatch(a -> a.annotationType() == Nullable.class);
  }

  // A plain field keeps its schema, format and all, with null added to its types. A reference
  // can't take a type of its own, so it becomes one of itself or null.
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Schema<?> orNull(Schema schema) {
    Set<String> types = schema.getTypes();
    if (schema.get$ref() == null && types != null && !types.isEmpty()) {
      if (!types.contains("null")) {
        Set<String> withNull = new LinkedHashSet<>(types);
        withNull.add("null");
        schema.setTypes(withNull);
        if (schema.getEnum() != null && !schema.getEnum().contains(null)) {
          List<Object> values = new ArrayList<>(schema.getEnum());
          values.add(null);
          schema.setEnum(values);
        }
      }
      return schema;
    }
    if (isOrNull(schema)) {
      return schema;
    }
    return new JsonSchema().anyOf(List.of(schema, new JsonSchema().types(Set.of("null"))));
  }

  private static boolean isOrNull(Schema<?> schema) {
    List<Schema> anyOf = schema.getAnyOf();
    return anyOf != null
        && anyOf.stream().anyMatch(s -> s.getTypes() != null && s.getTypes().contains("null"));
  }
}
