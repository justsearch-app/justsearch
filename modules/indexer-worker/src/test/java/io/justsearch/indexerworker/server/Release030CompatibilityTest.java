/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.ParityDiagnostics;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** U2/U3/U4: catalog permission is additive, explicit, and cannot hide another physical change. */
@Timeout(90)
final class Release030CompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void everyOpenPathAcceptsTheReleaseAndNewCommitStillDetectsRealChanges(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path generation = generation(root);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = Release030UpgradeCharacterizationTest.configure(root);
    try (var executors = new TestEngineExecutors();
        var registrations = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors)) {
      var expected = expected(readMetadata(generation));
      assertTrue(IndexMetadataParityGuard.inspectCommittedParity(generation, () -> expected).isEmpty());
      var schema = IndexSchema.fromCatalog(WorkerBootFixture.productionCatalog(), () -> expected,
          new JsonSchemaCommitMetadataValidator());
      var builder = schema.atPath(generation).withExecutorRegistrations(registrations)
          .withIndexOpenGuard(new IndexMetadataParityGuard(() -> generation, () -> expected));
      try (var deferred = assertDoesNotThrow(builder::openDeferred)) {
        // Production deferred activation runs the same guard through prepareWriterUpgrade.
        try (var prepared = assertDoesNotThrow(deferred::prepareWriterUpgrade)) {
          prepared.runtime().commitOps().commitAndTrack(CommitReason.DRAIN);
        }
      }
      try (var writable = assertDoesNotThrow(builder::open)) {
        assertTrue(writable.isAcceptingWrites());
        writable.commitOps().commitAndTrack(CommitReason.DRAIN);
      }
      var stored = readMetadata(generation);
      assertEquals(expected.get("index_fingerprint"), stored.get("index_fingerprint"));
      assertTrue(ParityDiagnostics.diff(stored, expected, 30, List.of()).isEmpty());
      ObjectNode changed = (ObjectNode) JSON.readTree((String) expected.get("index_fingerprint_inputs"));
      ((ObjectNode) changed.get("chunking")).put("target_tokens", 17);
      var incompatible = stamp(expected, changed);
      assertTrue(ParityDiagnostics.requiresRebuild(ParityDiagnostics.diff(stored, incompatible, 30, List.of())),
          "first commit must not disguise a later real shape change");
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void inputsOnlyAllowsOnlyMarkedAdditions(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Map<String, String> released = readMetadata(generation(root));
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = Release030UpgradeCharacterizationTest.configure(root);
    try (var executors = new TestEngineExecutors();
        var registrations = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors)) {
      var expected = expected(released);
      var inputsOnly = new HashMap<>(released);
      inputsOnly.remove("index_fingerprint");
      assertTrue(ParityDiagnostics.diff(inputsOnly, expected, 30, List.of()).isEmpty(),
          "U4: inputs-only commits get the same additive decision");
      // Exercise a real inputs-only commit: an unresolved model must not invent a digest, and
      // resolving it on the next open must not turn the compatible upgrade into a rebuild.
      var inputs = JSON.readTree(released.get("index_fingerprint_inputs"));
      var unresolvedSource = new SsotCommitMetadataSource(ConfigStore.global().get(),
          new SsotCommitMetadataSource.RuntimeFingerprintInputs(null,
              IndexFingerprint.ModelFingerprint.indeterminate(),
              IndexFingerprint.ModelFingerprint.present(inputs.get("splade_model_sha256").asText()),
              IndexFingerprint.ModelFingerprint.present(inputs.get("ner_model_sha256").asText())));
      var unresolved = unresolvedSource.build();
      var schema = IndexSchema.fromCatalog(WorkerBootFixture.productionCatalog(), () -> unresolved,
          new JsonSchemaCommitMetadataValidator());
      try (var writer = schema.atPath(generation(root)).withExecutorRegistrations(registrations)
          .withIndexOpenGuard(new IndexMetadataParityGuard(() -> generation(root), () -> unresolved,
              unresolvedSource::indeterminateFingerprintInputs)).open()) {
        writer.commitOps().commitAndTrack(CommitReason.DRAIN);
      }
      var committed = readMetadata(generation(root));
      assertFalse(committed.containsKey("index_fingerprint"), "unresolved inputs cannot claim a digest");
      assertTrue(JSON.readTree(committed.get("index_fingerprint_inputs"))
          .get("embedding_model_sha256").isNull());
      assertTrue(IndexMetadataParityGuard.inspectCommittedParity(generation(root), () -> expected).isEmpty());
      var resolvedSchema = IndexSchema.fromCatalog(WorkerBootFixture.productionCatalog(), () -> expected,
          new JsonSchemaCommitMetadataValidator());
      try (var writer = resolvedSchema.atPath(generation(root)).withExecutorRegistrations(registrations)
          .withIndexOpenGuard(new IndexMetadataParityGuard(() -> generation(root), () -> expected)).open()) {
        assertTrue(writer.isAcceptingWrites());
        assertTrue(writer.indexCountOps().docCountOrThrow() > 0, "release content survives both commits");
        writer.commitOps().commitAndTrack(CommitReason.DRAIN);
      }
      assertEquals(expected.get("index_fingerprint"), readMetadata(generation(root)).get("index_fingerprint"));
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void knownShapeChangesRemainMismatchesAndUncertaintyStaysDistinct(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Map<String, String> released = readMetadata(generation(root));
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = Release030UpgradeCharacterizationTest.configure(root);
    try {
      var expected = expected(released);
      var inputsOnly = new HashMap<>(released);
      inputsOnly.remove("index_fingerprint");
      for (String change : List.of("dimension", "analyzer", "chunking", "model", "role", "unmarked")) {
        ObjectNode inputs = (ObjectNode) JSON.readTree((String) expected.get("index_fingerprint_inputs"));
        switch (change) {
          case "dimension" -> {
            for (var field : inputs.get("fields")) {
              if (field.has("vector")) ((ObjectNode) field.get("vector")).put("dimension", 17);
            }
          }
          case "analyzer" -> inputs.put("analyzer_fp", "changed-analyzer");
          case "chunking" -> ((ObjectNode) inputs.get("chunking")).put("target_tokens", 17);
          case "model" -> inputs.put("embedding_model_sha256", "a".repeat(64));
          case "role" -> {
            for (var field : inputs.get("fields")) {
              if ("projection_digest".equals(field.path("id").asText())) {
                ((ObjectNode) field).putArray("roles").add("filter");
              }
            }
          }
          case "unmarked" -> {
            var fields = (tools.jackson.databind.node.ArrayNode) inputs.get("fields");
            var field = fields.addObject();
            field.put("id", "unmarked_addition"); field.put("type", "keyword");
            field.put("stored", true); field.put("doc_values", false); field.putArray("roles");
          }
          default -> throw new AssertionError(change);
        }
        var changed = stamp(expected, inputs);
        for (var stored : List.of(released, inputsOnly)) {
          assertTrue(ParityDiagnostics.requiresRebuild(ParityDiagnostics.diff(stored, changed, 30, List.of())), change);
        }
        var guard = new IndexMetadataParityGuard(() -> generation(root), () -> changed, List::of);
        assertEquals(IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
            assertThrows(IndexRuntimeIOException.class, guard::checkOnOpen).reason(), change);
      }
      var unreadable = new HashMap<>(inputsOnly);
      unreadable.put("index_fingerprint_inputs", "unreadable");
      assertTrue(ParityDiagnostics.storedShapeUnreadable(unreadable, expected));
      assertFalse(ParityDiagnostics.requiresRebuild(ParityDiagnostics.diff(unreadable, expected, 30, List.of())));
      assertFalse(ParityDiagnostics.requiresRebuild(ParityDiagnostics.diff(released, expected, 0, List.of())));
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  static Path generation(Path root) {
    return root.resolve("data/index/default/indices")
        .resolve(Release030UpgradeCharacterizationTest.state(root).active_generation());
  }

  static Map<String, String> readMetadata(Path generation) throws Exception {
    try (var directory = FSDirectory.open(generation); var reader = DirectoryReader.open(directory)) {
      return reader.getIndexCommit().getUserData();
    }
  }

  static Map<String, Object> expected(Map<String, String> stored) throws Exception {
    var inputs = JSON.readTree(stored.get("index_fingerprint_inputs"));
    return new SsotCommitMetadataSource(ConfigStore.global().get(),
        new SsotCommitMetadataSource.RuntimeFingerprintInputs(null,
            IndexFingerprint.ModelFingerprint.present(inputs.get("embedding_model_sha256").asText()),
            IndexFingerprint.ModelFingerprint.present(inputs.get("splade_model_sha256").asText()),
            IndexFingerprint.ModelFingerprint.present(inputs.get("ner_model_sha256").asText()))).build();
  }

  private static Map<String, Object> stamp(Map<String, Object> base, ObjectNode inputs) throws Exception {
    var metadata = new HashMap<>(base);
    String json = JSON.writeValueAsString(inputs);
    metadata.put("index_fingerprint_inputs", json);
    metadata.put("index_fingerprint", Release030Fixture.sha256(json.getBytes(StandardCharsets.UTF_8)));
    return metadata;
  }
}
