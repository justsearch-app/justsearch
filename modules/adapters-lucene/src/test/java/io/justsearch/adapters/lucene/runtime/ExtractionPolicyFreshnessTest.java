/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ExtractionPolicyFreshnessTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;
  private static final String ID = "document.txt";
  private static final String POLICY = "configured-policy";
  private static final String SOURCE = "a".repeat(64);

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"older-policy", "missing-policy"})
  void unknownOrOlderPolicyRequiresExtraction(String state) {
    try (var runtime = open()) {
      seed(runtime, state, false);
      assertTrue(runtime.documentFieldOps().isUnmodified(ID, 123, 42, SOURCE));
      assertFalse(current(runtime), "complete source evidence cannot certify another or unknown extraction policy");
    }
  }

  @Test
  void matchingPolicyIsStableAfterReopening() {
    try (var runtime = open()) {
      seed(runtime, POLICY, false);
      assertTrue(current(runtime));
      assertTrue(current(runtime));
    }
    try (var runtime = open()) {
      assertTrue(current(runtime));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"missing-size", "missing-hash", "different-hash", "incomplete-chunks", "deleted", "replaced"})
  void currentPolicyCannotBypassOtherRepairReasons(String reason) {
    try (var runtime = open()) {
      seed(runtime, reason, reason.equals("incomplete-chunks"));
      runtime.commitOps().suspendNrtRefresh();
      if (reason.equals("deleted")) runtime.indexingCoordinator().deleteById(ID);
      if (reason.equals("replaced")) runtime.indexingCoordinator().updateDocument(ID, Map.of(SchemaFields.SIZE_BYTES, 99L));
      runtime.commitOps().commitAndTrack();
      assertFalse(current(runtime), "policy match must preserve repair for " + reason);
    }
  }

  private boolean current(RunningRuntime runtime) {
    return runtime.documentFieldOps().isUnmodified(ID, 123, 42, SOURCE, POLICY);
  }

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(temporary.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static void seed(RunningRuntime runtime, String state, boolean incomplete) {
    String content = "short content";
    String revision = ChunkParentRevision.sha256Hex(content);
    var fields = new HashMap<String, Object>();
    fields.put(SchemaFields.DOC_ID, ID);
    fields.put(SchemaFields.DOC_UID, "uid");
    fields.put(SchemaFields.PATH, ID);
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.CONTENT_SHA256, revision);
    fields.put(SchemaFields.MODIFIED_AT, 123L);
    fields.put(SchemaFields.CHUNK_TOTAL, incomplete ? 1 : 0);
    fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
    if (!state.equals("missing-size")) fields.put(SchemaFields.SIZE_BYTES, 42L);
    if (!state.equals("missing-hash")) fields.put(SchemaFields.SOURCE_SHA256,
        state.equals("different-hash") ? "b".repeat(64) : SOURCE);
    if (!state.equals("missing-policy")) fields.put(SchemaFields.EXTRACTION_POLICY_ID,
        state.equals("older-policy") ? "older-policy" : POLICY);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
