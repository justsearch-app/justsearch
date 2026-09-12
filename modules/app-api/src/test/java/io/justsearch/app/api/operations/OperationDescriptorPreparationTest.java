/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

final class OperationDescriptorPreparationTest {
  @org.junit.jupiter.api.io.TempDir Path directory;

  private Map<String, Object> root() {
    return Map.of("path", directory.toString(), "collection", "default", "force", false,
        "singleFile", false, "excludePatterns", List.of(), "excludedSubtrees", List.of());
  }

  private String payload(Map<String, Object> root) {
    return JsonMapper.builder().build().writeValueAsString(Map.of("generation", "g1", "roots", List.of(root)));
  }
  @Test
  void preparedProjectionPreservesPublicDigestWithoutPublicContent() {
    String arguments = "{\"body\":\"private-text\"}";
    var descriptor = OperationDescriptor.preparedInvocation(OperationKind.OPERATION, "core.reindex",
        arguments, "root-plan.v1", payload(root()));
    var tree = JsonMapper.builder().build().readTree(descriptor.identityJson());
    assertEquals("invoke", tree.path("mode").asText());
    assertEquals(CanonicalOperationArguments.digest(arguments), tree.path("argumentsSha256").asText());
    assertEquals("root-plan.v1", tree.path("preparedInvocation").path("schema").asText());
    assertEquals(directory.toString(), tree.path("preparedInvocation").path("payload")
        .path("roots").get(0).path("path").asText());
    assertFalse(descriptor.identityJson().contains("private-text"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"prompt", "body", "messages", "canonicalArguments"})
  void contentFieldsCannotEnterSafeReplayProjection(String field) {
    var root = new java.util.HashMap<>(root());
    root.put(field, "forbidden-content");
    assertThrows(IllegalArgumentException.class, () -> OperationDescriptor.preparedInvocation(
        OperationKind.OPERATION, "core.reindex", "{}", "root-plan.v1", payload(root)));
    String topLevel = payload(root()).replace("\"roots\":", "\"" + field + "\":\"forbidden-content\",\"roots\":");
    assertThrows(IllegalArgumentException.class, () -> OperationDescriptor.preparedInvocation(
        OperationKind.OPERATION, "core.reindex", "{}", "root-plan.v1", topLevel));
  }

  @Test
  void rejectsUnsupportedSchemaRelativePathsAndEscapingSubtrees() {
    assertThrows(IllegalArgumentException.class, () -> OperationDescriptor.preparedInvocation(
        OperationKind.OPERATION, "core.reindex", "{}", "content-plan.v1", payload(root())));
    for (Map<String, Object> changes : List.of(Map.<String, Object>of("path", "relative-path"),
        Map.<String, Object>of("force", 1),
        Map.<String, Object>of("excludePatterns", List.of(Map.of("body", "private"))),
        Map.<String, Object>of("excludedSubtrees", List.of(directory.resolveSibling("elsewhere").toString())))) {
      var root = new java.util.HashMap<>(root());
      root.putAll(changes);
      assertThrows(IllegalArgumentException.class, () -> OperationDescriptor.preparedInvocation(
          OperationKind.OPERATION, "core.reindex", "{}", "root-plan.v1", payload(root)));
    }
  }

  @Test
  void malformedOrNonObjectReplayProjectionIsRefused() {
    for (String payload : new String[] {"[", "[]", "null", "\"string\""}) {
      assertThrows(RuntimeException.class, () -> OperationDescriptor.preparedInvocation(
          OperationKind.OPERATION, "core.reindex", "{}", "root-plan.v1", payload));
    }
  }
}
