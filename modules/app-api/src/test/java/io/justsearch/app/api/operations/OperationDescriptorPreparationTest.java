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

  @Test
  void ingestChildRetainsExactlyOneRecordedPartitionAndParentKey() {
    var root = new java.util.HashMap<>(root());
    root.put("excludedSubtrees", List.of(directory.resolve("nested").toString()));
    var plan = RecordedRootPlan.fromReplayPayload(payload(root));
    var parent = OperationDescriptor.preparedInvocation(OperationKind.REINDEX, "core.parent-fixture",
        "{}", RecordedRootPlan.SCHEMA, plan.toReplayPayload());
    assertEquals(plan, parent.recordedRootPlan());
    String key = OperationKeys.generate(java.time.Clock.systemUTC());
    var child = OperationDescriptor.ingestChild(key, parent.recordedRootPlan());
    assertEquals(OperationKind.INGEST, child.kind());
    org.junit.jupiter.api.Assertions.assertNull(child.operationRef());
    assertEquals(plan, child.recordedRootPlan());
    assertEquals(List.of(directory.resolve("nested")), child.recordedRootPlan().roots().getFirst().excludedSubtrees());
    var json = JsonMapper.builder().build().readTree(child.identityJson());
    assertEquals(java.util.Set.of("mode", "parentOperationKey", "preparedInvocation"), json.propertyNames());
    assertEquals("ingest-child", json.path("mode").asText());
    assertEquals(key, json.path("parentOperationKey").asText());
    assertThrows(IllegalArgumentException.class, () -> OperationDescriptor.ingestChild(key,
        new RecordedRootPlan(plan.generation(), List.of())));
    assertThrows(IllegalArgumentException.class, () -> OperationDescriptor.ingestChild(key,
        new RecordedRootPlan(plan.generation(), List.of(plan.roots().getFirst(), plan.roots().getFirst()))));
  }

  @Test
  void recordedRootDecoderRejectsAmbiguousIdentityAndUnsafePayload() {
    var parent = OperationDescriptor.preparedInvocation(OperationKind.REINDEX, "core.parent-fixture",
        "{}", RecordedRootPlan.SCHEMA, payload(root()));
    String valid = parent.identityJson();
    for (String invalid : List.of("null", "[]", "[", valid + " {}",
        valid.replace("\"mode\":\"invoke\"", "\"mode\":\"invoke\",\"mode\":\"invoke\""),
        valid.replace("\"mode\":\"invoke\"", "\"mode\":\"invoke\",\"extra\":true"),
        valid.replace(CanonicalOperationArguments.digest("{}"), "bad-digest"),
        valid.replace("\"schema\":\"root-plan.v1\"", "\"schema\":\"content-plan.v1\""),
        valid.replace("\"generation\":", "\"body\":\"private\",\"generation\":"))) {
      assertThrows(IllegalArgumentException.class, () ->
          new OperationDescriptor(OperationKind.REINDEX, "core.parent-fixture", invalid).recordedRootPlan());
    }
    assertThrows(IllegalArgumentException.class, () -> OperationDescriptor.invocation(
        OperationKind.REINDEX, "core.parent-fixture", "{}", false).recordedRootPlan());
    String key = OperationKeys.generate(java.time.Clock.systemUTC());
    var child = OperationDescriptor.ingestChild(key, parent.recordedRootPlan());
    assertThrows(IllegalArgumentException.class, () -> new OperationDescriptor(OperationKind.INGEST, null,
        child.identityJson().replace(key, "not-a-key")).recordedRootPlan());
    var childJson = JsonMapper.builder().build().readTree(child.identityJson());
    var roots = (tools.jackson.databind.node.ArrayNode) childJson.path("preparedInvocation").path("payload").path("roots");
    var duplicateRoot = roots.get(0).deepCopy();
    roots.add(duplicateRoot);
    assertThrows(IllegalArgumentException.class, () ->
        new OperationDescriptor(OperationKind.INGEST, null, childJson.toString()).recordedRootPlan());
  }

  @Test
  void recordedIdentityCannotBypassThePayloadSizeBound() {
    var root = new java.util.HashMap<>(root());
    root.put("excludePatterns", java.util.Collections.nCopies(1000, "a".repeat(204)));
    var mapper = JsonMapper.builder().build();
    String identity = mapper.writeValueAsString(Map.of("mode", "invoke",
        "argumentsSha256", CanonicalOperationArguments.digest("{}"), "preparedInvocation",
        Map.of("schema", RecordedRootPlan.SCHEMA, "payload", mapper.readTree(payload(root)))));
    org.junit.jupiter.api.Assertions.assertTrue(identity.length() > 200000 && identity.length() < 262144);
    assertThrows(IllegalArgumentException.class, () ->
        new OperationDescriptor(OperationKind.REINDEX, "core.parent-fixture", identity).recordedRootPlan());
  }

  @Test
  void identityBoundCountsUtf8BytesRatherThanJavaCharacters() {
    String identity = "{\"metadata\":\"" + "é".repeat(131072) + "\"}";
    org.junit.jupiter.api.Assertions.assertTrue(identity.length() < 262144);
    assertThrows(IllegalArgumentException.class,
        () -> new OperationDescriptor(OperationKind.OPERATION, "core.test", identity));
  }
}
