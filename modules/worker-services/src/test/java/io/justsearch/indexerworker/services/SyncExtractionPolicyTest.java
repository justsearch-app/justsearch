/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SyncExtractionPolicyTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"old-policy", "missing-policy", "missing-hash", "configured-policy"})
  void reconciliationSelectsStaleExtractionThroughExistingAdmission(String stale) throws Exception {
    check(stale, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"default", "configured"})
  void currentEffectivePolicyKeepsUnchangedFilesSkipped(String policy) throws Exception {
    check(policy, false);
  }

  private void check(String state, boolean repair) throws Exception {
    Path file = Files.writeString(temporary.resolve("document.txt"), "source text");
    var policy = state.startsWith("configured")
        ? TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(1_000_000, 5_000_000L))
        : TikaExtractionPolicy.defaults();
    String id = PathNormalizer.normalizeKey(file);
    var fields = new HashMap<String, String>();
    fields.put(SchemaFields.PATH, id);
    fields.put(SchemaFields.MODIFIED_AT, String.valueOf(Files.getLastModifiedTime(file).toMillis()));
    fields.put(SchemaFields.SIZE_BYTES, String.valueOf(Files.size(file)));
    if (!state.equals("missing-policy")) fields.put(SchemaFields.EXTRACTION_POLICY_ID,
        repair ? TikaExtractionPolicy.defaults().policyId() : policy.policyId());
    if (state.equals("old-policy")) fields.put(SchemaFields.EXTRACTION_POLICY_ID, "tika-default-v7");
    if (!state.equals("missing-hash")) fields.put(SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file));
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenAnswer(call -> {
          java.util.Set<String> requested = call.getArgument(2);
          var projected = new HashMap<>(fields);
          projected.keySet().retainAll(requested);
          return new LuceneRuntimeTypes.SearchResult(
              List.of(new LuceneRuntimeTypes.SearchHit(id, 1f, projected)), 1, 0);
        });
    var queue = mock(JobQueue.class);
    var admitted = new ArrayList<Path>();
    org.mockito.stubbing.Answer<Integer> admission = call -> {
      List<JobQueue.EnqueueEntry> entries = call.getArgument(0);
      for (var entry : entries) admitted.add(entry.path());
      return entries.size();
    };
    when(queue.enqueueEntries(anyList())).thenAnswer(admission);
    when(queue.enqueueMaintenanceScanEntries(anyList(), nullable(String.class), any())).thenAnswer(admission);
    var ops = new SyncDirectoryOps(reads, null, null, queue, IndexingPacing.unthrottled());
    ops.setExtractionPolicyId(policy.policyId());
    List<Path> expected = repair ? List.of(file) : List.of();
    for (var mode : List.of(SyncDirectoryOps.AdmissionMode.DEFAULT, SyncDirectoryOps.AdmissionMode.MAINTENANCE)) {
      admitted.clear();
      var result = ops.execute(temporary.toString(), false, null, () -> false, null, mode);
      assertEquals("", result.getError());
      assertEquals(expected, admitted, "unchanged source with " + state + " in " + mode);
      assertEquals(expected.size(), result.getFilesAdded());
    }
    try (var difference = ops.discoverCandidateDifference(temporary.toString(), false, () -> false)) {
      assertEquals(expected, difference.additions());
    }
    if (repair) {
      verify(queue).enqueueMaintenanceScanEntries(anyList(), isNull(), any());
      fields.put(SchemaFields.EXTRACTION_POLICY_ID, policy.policyId());
      fields.put(SchemaFields.SOURCE_SHA256, SourceContentHash.sha256(file));
      admitted.clear();
      assertEquals(0, ops.execute(temporary.toString(), false, null, () -> false, null,
          SyncDirectoryOps.AdmissionMode.MAINTENANCE).getFilesAdded());
      assertTrue(admitted.isEmpty(), "repaired evidence becomes stable");
    } else verifyNoInteractions(queue);
  }
}
