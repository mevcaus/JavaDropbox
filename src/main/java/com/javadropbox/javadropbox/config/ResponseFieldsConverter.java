package com.javadropbox.javadropbox.config;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Says in the API spec which fields of a response are always there and which can be null, so the
 * frontend's generated types are exact.
 *
 * <p>Jackson writes every field, nulls included, so every field of our types is required. One is
 * nullable, {@code T | null} in the generated types, only when its type is marked {@link Nullable}:
 * on a record component, or on a getter's return type. Unmarked means never null.
 *
 * <p>It also puts back a field swagger-core leaves out of a type that refers to itself.
 *
 * <p>Every schema in the spec is a response today. A type used as a request body would need its own
 * rules, since a client need not send every field.
 */
@Component
public class ResponseFieldsConverter implements ModelConverter {

  private static final ObjectMapper JACKSON = new ObjectMapper();

  @Override
  public Schema<?> resolve(
      AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    if (!chain.hasNext()) {
      return null;
    }
    Schema<?> resolved = chain.next().resolve(type, context, chain);
    if (resolved == null || type.getType() == null) {
      return resolved;
    }
    JavaType javaType = JACKSON.constructType(type.getType());
    if (!javaType.getRawClass().getPackageName().startsWith("com.javadropbox")) {
      return resolved;
    }
    Schema<?> model = model(resolved, context);
    if (model == null || model.getProperties() == null) {
      return resolved;
    }
    Map<String, Schema> properties = model.getProperties();
    for (BeanPropertyDefinition property :
        JACKSON.getSerializationConfig().introspect(javaType).findProperties()) {
      String name = property.getName();
      Schema<?> schema = properties.get(name);
      if (schema == null) {
        // swagger-core drops a field that refers back to its own type when it first meets that
        // type inside another one, as with SharedItemDto.Entry's children. Jackson still sends it.
        schema = context.resolve(new AnnotatedType(property.getPrimaryType()).resolveAsRef(true));
        if (schema == null) {
          continue;
        }
        model.addProperty(name, schema);
      }
      if (model.getRequired() == null || !model.getRequired().contains(name)) {
        model.addRequiredItem(name);
      }
      if (isNullable(property) && !isOrNull(schema)) {
        properties.put(name, orNull(schema));
      }
    }
    return resolved;
  }

  // The resolver hands back either the model itself or a reference to it.
  private static Schema<?> model(Schema<?> resolved, ModelConverterContext context) {
    if (resolved.get$ref() == null) {
      return resolved;
    }
    String ref = resolved.get$ref();
    return context.getDefinedModels().get(ref.substring(ref.lastIndexOf('/') + 1));
  }

  // A record component's type annotations are on its field and its accessor alike.
  private static boolean isNullable(BeanPropertyDefinition property) {
    AnnotatedMethod getter = property.getGetter();
    if (getter != null
        && getter.getAnnotated().getAnnotatedReturnType().isAnnotationPresent(Nullable.class)) {
      return true;
    }
    AnnotatedField field = property.getField();
    return field != null
        && field.getAnnotated().getAnnotatedType().isAnnotationPresent(Nullable.class);
  }

  private static Schema<?> orNull(Schema<?> schema) {
    return new JsonSchema().anyOf(List.of(schema, new JsonSchema().types(Set.of("null"))));
  }

  private static boolean isOrNull(Schema<?> schema) {
    List<Schema> anyOf = schema.getAnyOf();
    return anyOf != null
        && anyOf.stream().anyMatch(s -> s.getTypes() != null && s.getTypes().contains("null"));
  }
}
