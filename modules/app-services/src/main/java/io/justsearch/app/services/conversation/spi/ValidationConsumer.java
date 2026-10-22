/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates extraction output in the Engine, shared by every client. */
public final class ValidationConsumer implements StreamConsumer {
  public static final String ID = "core.validation-consumer";
  private static final JsonMapper MAPPER = JsonMapper.builder()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
      SpecificationVersion.DRAFT_2020_12,
      builder -> builder.schemaLoader(loader -> loader.block(iri -> true)));
  // The dependency bundles the standard drafts and their meta-schemas. Only this trusted
  // registry loads them; user schemas still cannot load external references through SCHEMAS.
  private static final SchemaRegistry META_SCHEMAS = SchemaRegistry.withDefaultDialect(
      SpecificationVersion.DRAFT_2020_12,
      builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));

  @Override public String id() { return ID; }

  @Override
  public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
    return StreamConsumerResult.empty();
  }

  @Override
  public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
    Object raw = ctx.requestBody().getOrDefault("schema", "{}");
    final com.networknt.schema.Schema schema;
    try {
      if (!(raw instanceof String text)) throw new IllegalArgumentException("Schema must be a JSON object");
      JsonNode node = MAPPER.readTree(text);
      if (node == null || !node.isObject()) throw new IllegalArgumentException("Schema must be a JSON object");
      schema = SCHEMAS.getSchema(node);
      var problems = META_SCHEMAS.getSchema(SchemaLocation.of(schema.getSchemaContext().getDialect().getId()))
          .validate(node);
      if (!problems.isEmpty()) {
        throw new IllegalArgumentException(problems.stream().map(error -> error.getInstanceLocation() + ": " + error.getMessage())
            .sorted().reduce((a, b) -> a + "; " + b).orElseThrow());
      }
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Cannot apply extraction schema: " + e.getMessage(), e);
    }
    String problem;
    try {
      JsonNode output = MAPPER.readTree(fullText);
      if (output == null) throw new IllegalArgumentException("Empty JSON output");
      var errors = schema.validate(output);
      problem = errors.stream().map(com.networknt.schema.Error::getMessage)
          .sorted().reduce((a, b) -> a + "; " + b).orElse("");
    } catch (tools.jackson.core.JacksonException e) {
      problem = "Output is not valid JSON: " + e.getOriginalMessage();
    } catch (RuntimeException e) {
      // A reference or unsupported dialect that cannot be resolved is a failure, not a pass.
      throw new IllegalArgumentException("Cannot apply extraction schema: " + e.getMessage(), e);
    }
    boolean passed = problem.isEmpty();
    ctx.attributes().put("validation.passed", passed);
    ctx.attributes().put("validation.reason", problem);
    if (passed) return StreamConsumerResult.empty();
    String correction = "Extraction validation failed: " + problem
        + "\nRespond with ONLY valid JSON matching this schema:\n" + raw;
    return new StreamConsumerResult(List.of(), List.of(),
        List.of(Map.of("role", "user", "content", correction)), Map.of());
  }
}
