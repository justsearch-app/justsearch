/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LossyDecodingStoredDocumentTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  @Test
  void lossSurvivesReopenAndFileRepairClearsReason() throws Exception {
    exerciseRepair(false);
  }

  @Test
  void combinedSurvivesReopenAndFileRepairKeepsTruncationReason() throws Exception {
    exerciseRepair(true);
  }

  private void exerciseRepair(boolean truncated) throws Exception {
    Path file = tempDir.resolve("document.txt");
    String source = "valid searchablewords ".repeat(truncated ? 100 : 1);
    byte[] clean = ("\uFEFF" + source).getBytes(StandardCharsets.UTF_8);
    byte[] lossy = java.util.Arrays.copyOf(clean, clean.length + 1);
    lossy[clean.length] = (byte) 0xff;
    Files.write(file, lossy);
    var policy = truncated ? TikaExtractionPolicy.fromWorkerLimits(new ResolvedConfig.Worker(80, 10_000_000))
        : TikaExtractionPolicy.defaults();
    String reason = truncated ? "TEXT_LOST_WHILE_DECODING_AND_TRUNCATED" : "TEXT_LOST_WHILE_DECODING";
    try (var runtime = open()) {
      index(runtime, file, policy);
      assertStored(runtime, file, "SUCCESS_PARTIAL", reason, truncated);
    }
    try (var runtime = open()) {
      assertStored(runtime, file, "SUCCESS_PARTIAL", reason, truncated);
      Files.write(file, clean);
      index(runtime, file, policy);
      assertStored(runtime, file, truncated ? "SUCCESS_PARTIAL" : "SUCCESS_FULL",
          truncated ? "SUCCESS_PARTIAL" : null, truncated);
    }
    try (var runtime = open()) {
      assertStored(runtime, file, truncated ? "SUCCESS_PARTIAL" : "SUCCESS_FULL",
          truncated ? "SUCCESS_PARTIAL" : null, truncated);
    }
  }

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(tempDir.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static void index(RunningRuntime runtime, Path file, TikaExtractionPolicy policy) throws Exception {
    try (var parser = new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(), policy)) {
      var artifact = parser.extractArtifact(file).validate(policy, "hash");
      var document = IndexingDocumentOps.buildDocument(file, artifact, null, mock(WorkerSignalBus.class),
          null, false, null, null, (stage, ms, reason) -> {},
          org.slf4j.LoggerFactory.getLogger(LossyDecodingStoredDocumentTest.class), null,
          new IndexingDocumentOps.SourceFileMetadata(Files.size(file), Files.getLastModifiedTime(file).toMillis()),
          "stable-uid");
      runtime.indexingCoordinator().indexSingle(document);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
    }
  }

  private static void assertStored(RunningRuntime runtime, Path file, String status, String reason,
      boolean truncated) {
    String id = PathNormalizer.normalizeKey(file);
    assertEquals(status, runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_STATUS));
    assertEquals(reason, runtime.documentFieldOps().getDocumentField(id, SchemaFields.EXTRACTION_REASON_CODE));
    assertTrue(runtime.documentFieldOps().getDocumentContent(id).contains("valid searchablewords"));
    assertFalse(runtime.textQueryOps().searchText("searchablewords", 10, null).hits().isEmpty());
    FetchDocumentSliceResponse response = new WorkerSearchService(runtime).fetchDocumentSlice(
        FetchDocumentSliceRequest.newBuilder().setDocId(id).setMaxChars(200).build(),
        io.justsearch.indexerworker.services.CallContext.none());
    assertNotNull(response);
    assertTrue(response.getFound());
    assertEquals(status, response.getExtractionStatus());
    assertEquals(truncated, response.getContentTruncated());
    assertEquals(reason == null ? "" : reason,
        response.getMetadataOrDefault("extraction_reason_code", ""));
  }
}
