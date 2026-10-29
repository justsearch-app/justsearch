/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationRef;
import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 3a-2-c Phase C: JSON Schema validator for Operation arguments.
 *
 * <p>Validates {@code argumentsJson} against the schema declared on
 * {@link Operation#interface_()}'s {@code inputs} field. Before this validator
 * existed, each handler reimplemented arg parsing + presence checks via
 * Jackson tree-walking; the schema declarations were decorative. This validator
 * makes the substrate's declarative schema enforcement live.
 *
 * <p>Schema compilation is cached per {@link OperationRef} for performance;
 * concurrent dispatch reuses the same {@link Schema} instance. The cache
 * is unbounded — Operations are seeded at catalog construction (a few dozen
 * entries), not user-data driven, so unbounded growth isn't a concern.
 *
 * <p>Mirrors the pattern in
 * {@code adapters-lucene/.../JsonSchemaCommitMetadataValidator} (DRAFT_2020_12,
 * networknt/json-schema-validator).
 */
public final class OperationInputSchemaValidator {

  private static final Logger log = LoggerFactory.getLogger(OperationInputSchemaValidator.class);

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  /** Empty schema sentinel — matches any input (used when an Operation declares no args). */
  private static final String EMPTY_SCHEMA = "{\"type\":\"object\"}";

  private final ConcurrentHashMap<String, Schema> compiledByOpId = new ConcurrentHashMap<>();

  /**
   * Validate {@code argumentsJson} against {@code op}'s declared input schema.
   *
   * @return {@link Optional#empty()} when valid; populated with an aggregated
   *     human-readable message when invalid.
   */
  public Optional<ValidationResult> validate(Operation op, String argumentsJson) {
    return validateAgainstSchema(op.id().value(), op.intf().inputs(), argumentsJson);
  }

  /**
   * Tempdoc 655: validate against a declared schema directly, for callers with no {@link
   * Operation} to validate against — concretely, MCP tools dispatched in-process rather than
   * through an Operation (answer/search/status/runtime_manifest in {@code McpToolSurface}).
   * {@code cacheKey} need only be stable and unique per schema (e.g. the MCP tool name); it plays
   * the same role {@code op.id().value()} plays for the Operation-backed overload.
   */
  public Optional<ValidationResult> validate(
      String cacheKey, String schemaJson, String argumentsJson) {
    return validateAgainstSchema(cacheKey, schemaJson, argumentsJson);
  }

  private Optional<ValidationResult> validateAgainstSchema(
      String cacheKey, String inputs, String argumentsJson) {
    // Effectively-empty schemas don't need validation. Treat a missing or all-whitespace
    // schema as "no constraints" rather than failing closed.
    if (inputs == null || inputs.isBlank() || EMPTY_SCHEMA.equals(inputs.trim())) {
      return Optional.empty();
    }

    JsonNode argsNode;
    try {
      argsNode = MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
    } catch (Exception e) {
      return Optional.of(
          new ValidationResult(
              "Invalid JSON in args: "
                  + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
              Map.of("parseError", e.getClass().getSimpleName())));
    }

    Schema schema;
    try {
      schema = compiledByOpId.computeIfAbsent(cacheKey, id -> compile(id, inputs));
    } catch (RuntimeException e) {
      // Schema compile failure is a substrate-level bug (the declared schema is malformed).
      // Log but don't block the dispatch — the handler may still validate. Treat as
      // "not validated" rather than "invalid input."
      log.warn("Failed to compile input schema for {}: {}", cacheKey, e.getMessage());
      return Optional.empty();
    }

    List<com.networknt.schema.Error> violations = schema.validate(argsNode);
    if (violations.isEmpty()) {
      return Optional.empty();
    }

    String message = aggregate(violations);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("operationId", cacheKey);
    details.put("violations", violations.stream().map(OperationInputSchemaValidator::describe).toList());
    return Optional.of(new ValidationResult(message, details));
  }

  private static Schema compile(String operationId, String inputsSchemaJson) {
    JsonNode schemaNode;
    try {
      schemaNode = MAPPER.readTree(inputsSchemaJson);
    } catch (Exception e) {
      throw new RuntimeException(
          "Operation " + operationId + " input schema is not valid JSON", e);
    }
    SchemaContext ctx =
        new SchemaContext(
            REGISTRY.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), REGISTRY);
    // SchemaLocation requires a URI; synthesize one from the operation id so
    // schema-internal $ref resolution (none expected today) gets a stable base.
    SchemaLocation location =
        SchemaLocation.of(URI.create("urn:operation-input:" + operationId).toString());
    return ctx.newSchema(location, schemaNode, null);
  }

  private static String describe(com.networknt.schema.Error violation) {
    var location = violation.getInstanceLocation();
    // Required/additional-property errors point at the containing object, not the field.
    if (violation.getProperty() != null) location = location.append(violation.getProperty());
    String field = location.toString();
    return (field.isEmpty() ? "/" : field) + ": " + violation.getMessage();
  }

  private static String aggregate(List<com.networknt.schema.Error> violations) {
    return violations.stream()
        .sorted(Comparator.comparing(com.networknt.schema.Error::getMessage))
        .map(OperationInputSchemaValidator::describe)
        .reduce((a, b) -> a + "; " + b)
        .orElse("");
  }

  /** Container for a validation failure — message + structured details. */
  public record ValidationResult(String message, Map<String, Object> details) {}
}
