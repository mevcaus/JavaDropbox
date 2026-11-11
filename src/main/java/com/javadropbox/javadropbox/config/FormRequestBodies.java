package com.javadropbox.javadropbox.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;

/**
 * Describes the fields of a form or multipart request as its body, so a client generated from the
 * spec sends them the way the endpoint takes them.
 *
 * <p>Spring reads a {@code @RequestParam} from the query string and from a form body alike, so
 * springdoc lists every one as a query parameter. Given {@code consumes}, it does make a body, but
 * collapses one with a single field to that field's bare schema, nameless, and leaves files sent as
 * {@code @RequestParam MultipartFile[]} in the query. So for every endpoint whose mapping consumes
 * a form or multipart, this builds the body from the method's {@code @RequestParam}s: one field
 * each, under its name, with the schema springdoc gave it, required unless it is optional or has a
 * default. Those parameters are then left out of the query.
 *
 * <p>Building the spec fails when a field can't be found, or an endpoint mixes a form with {@code
 * RequestPart}, rather than leaving a field in the query. A form bound to an object ({@code
 * ModelAttribute}) isn't handled.
 */
@Component
public class FormRequestBodies implements OperationCustomizer {

  private static final List<String> FORMS =
      List.of(
          org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE,
          org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE);

  @Override
  public Operation customize(Operation operation, HandlerMethod handler) {
    List<String> consumes = consumes(handler).stream().filter(FORMS::contains).toList();
    if (consumes.isEmpty()) {
      return operation;
    }

    Map<String, Schema> fields = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    String endpoint = handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
    long formFields =
        java.util.Arrays.stream(handler.getMethod().getParameters())
            .filter(FormRequestBodies::isFormField)
            .count();
    for (java.lang.reflect.Parameter parameter : handler.getMethod().getParameters()) {
      if (AnnotatedElementUtils.hasAnnotation(parameter, RequestPart.class)) {
        // Its part is already in the body springdoc made, which this would replace.
        throw new IllegalStateException(
            endpoint + " mixes @RequestPart with a form; FormRequestBodies can't describe it.");
      }
      // Merged, so @RequestParam("files") reads as the name "files".
      RequestParam param =
          AnnotatedElementUtils.findMergedAnnotation(parameter, RequestParam.class);
      if (param == null || !isFormField(parameter)) {
        continue;
      }
      String name = !param.name().isEmpty() ? param.name() : parameter.getName();
      Schema<?> schema = schemaOf(operation, name, formFields == 1);
      if (schema == null) {
        throw new IllegalStateException(
            "springdoc left " + endpoint + "'s form field \"" + name + "\" out of the spec.");
      }
      fields.put(name, schema);
      if (param.required() && param.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
        required.add(name);
      }
    }
    if (fields.isEmpty()) {
      return operation;
    }

    Schema<?> body = new JsonSchema().types(java.util.Set.of("object")).properties(fields);
    if (!required.isEmpty()) {
      body.required(required);
    }
    Content content = new Content();
    consumes.forEach(type -> content.addMediaType(type, new MediaType().schema(body)));
    RequestBody requestBody =
        operation.getRequestBody() == null ? new RequestBody() : operation.getRequestBody();
    // Always required, even when every field is optional: without a body there is no content type,
    // and the mapping refuses the request with a 415.
    operation.requestBody(requestBody.content(content).required(true));
    if (operation.getParameters() != null) {
      operation
          .getParameters()
          .removeIf(p -> "query".equals(p.getIn()) && fields.containsKey(p.getName()));
      if (operation.getParameters().isEmpty()) {
        operation.setParameters(null);
      }
    }
    return operation;
  }

  // The method's consumes, or its controller's when the method declares none, as Spring decides.
  private static List<String> consumes(HandlerMethod handler) {
    RequestMapping method =
        AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RequestMapping.class);
    if (method != null && method.consumes().length > 0) {
      return List.of(method.consumes());
    }
    RequestMapping type =
        AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), RequestMapping.class);
    return type == null ? List.of() : List.of(type.consumes());
  }

  // A @RequestParam that springdoc puts in the spec: not hidden, and not a Map of all parameters.
  private static boolean isFormField(java.lang.reflect.Parameter parameter) {
    if (!AnnotatedElementUtils.hasAnnotation(parameter, RequestParam.class)
        || Map.class.isAssignableFrom(parameter.getType())) {
      return false;
    }
    io.swagger.v3.oas.annotations.Parameter doc =
        parameter.getAnnotation(io.swagger.v3.oas.annotations.Parameter.class);
    return doc == null || !doc.hidden();
  }

  // Where springdoc put the field: as a query parameter, as a property of the body it made, or as
  // the whole body, when the form had only this one field.
  private static @Nullable Schema<?> schemaOf(Operation operation, String name, boolean only) {
    if (operation.getParameters() != null) {
      for (Parameter parameter : operation.getParameters()) {
        if ("query".equals(parameter.getIn()) && name.equals(parameter.getName())) {
          return parameter.getSchema();
        }
      }
    }
    if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
      return null;
    }
    for (MediaType media : operation.getRequestBody().getContent().values()) {
      Schema<?> schema = media.getSchema();
      if (schema == null) {
        continue;
      }
      if (schema.getProperties() != null) {
        Schema<?> field = schema.getProperties().get(name);
        if (field != null) {
          return field;
        }
      } else if (only) {
        // springdoc collapsed a one-field form to that field's schema.
        return schema;
      }
    }
    return null;
  }
}
