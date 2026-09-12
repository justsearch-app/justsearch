/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rich-document (PDF) ingestion, end to end, with no process boundary.
 *
 * <p>This is {@code RichDocumentIntegrationTest} (formerly
 * {@code modules/app-services/src/integrationTest/java/io/justsearch/app/services/worker/}),
 * converted by lane F stage A item A11. The property it pins is real and is covered nowhere else:
 * a document whose text has to be <em>extracted</em> rather than read — a PDF — survives ingest and
 * is findable by its extracted content. {@link EngineRootInProcessPortsTest} covers the same loop
 * for a plain text file; the extractor path is what this one adds.
 *
 * <p><b>Why it moved modules.</b> The reach changed, not the property. It used to spawn a Worker
 * process and talk to it over a port; it now composes the index half in this JVM through
 * {@link EngineRoot}, whose test constructor is package-private to {@code io.justsearch.app.engine}
 * — and {@code app-services} cannot see {@code app-engine} at all, because the dependency runs the
 * other way. So the test lives where the composition root lives.
 *
 * <p><b>What did not survive, and why.</b> Only things that were about the process:
 * {@code assertTrue(workerJarExists, "Worker JAR required for this test")} — a precondition on an
 * artifact that no longer participates — and the {@code @DisabledIfEnvironmentVariable(named =
 * "CI")} guard, whose stated reason was "spawns actual worker processes which require
 * environment-specific setup", no longer true of anything this test does. The slf4j progress
 * logging is dropped because {@code app-engine}'s test source set does not carry slf4j; the
 * convergence assertion already reports queue depth and doc count in its failure message, which is
 * where that diagnostic actually mattered. Every assertion about the PDF is verbatim, including
 * {@code client.isHealthy()}, which now asks the in-process client the same question.
 */
@Timeout(120)
final class RichDocumentInProcessTest {

  private EngineRoot root;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (root != null) {
      root.close();
      root = null;
    }
    // Give the OS time to release file locks on Windows before @TempDir cleanup. Carried over from
    // the process-boundary version: the locks are Lucene's, and closing the index half in-process
    // releases them no faster than closing a child process did.
    Thread.sleep(2000);
  }

  private static void publishConfig(Path dataDir, Path indexBase) throws IOException {
    Files.createDirectories(dataDir);
    Files.createDirectories(indexBase);
    ConfigStore.setGlobal(
        new ConfigStore(
            new ResolvedConfigBuilder()
                .contributeBaseSources()
                .putDefault("justsearch.data.dir", dataDir.toAbsolutePath().toString())
                .putDefault("justsearch.index.base_path", indexBase.toAbsolutePath().toString())
                .build()));
  }

  @Test
  @DisplayName("a PDF is extracted, indexed and found by its extracted content, in process")
  void ingestAndSearchPdf(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path indexBase = dataDir.resolve("index");
    publishConfig(dataDir, indexBase);

    // 1. Create a valid minimal PDF
    Path pdfFile = tempDir.resolve("test-document.pdf");
    createMinimalPdf(pdfFile);

    // 2. Compose the index half in this JVM (was: spawn a Worker process and connect to its port)
    root =
        new EngineRoot(org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class), org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
            g -> new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerConfig.load(), new InProcessWorkerSignalBus(g)),
            30_000L,
            5_000);
    KnowledgeClient client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
    assertNotNull(client, "the root must hand back a client");
    assertTrue(client.isHealthy(TestEngineContexts.FOREGROUND), "Worker should be healthy");

    // 3. Submit PDF
    BatchResponse batchResponse = client.submitBatch(List.of(pdfFile), TestEngineContexts.FOREGROUND);
    assertEquals(1, batchResponse.getAcceptedCount(), "Should accept PDF file");

    // 4. Wait for indexing completion (queue drain + doc-count convergence)
    waitForIndexing(client, 1);

    // 5. Search with bounded polling instead of fixed sleep.
    SearchResponse response = waitForSearchHits(client, "JustSearch", 10, 20);

    assertTrue(response.getResultsCount() > 0, "Should find PDF document");
    assertTrue(response.getResults(0).getScore() > 0, "Score should be positive");
  }

  private static void createMinimalPdf(Path path) throws IOException {
    // Valid minimal PDF 1.4 with content "JustSearch PDF Test"
    // Base64 encoded to avoid encoding issues across OS
    String b64 =
        "JVBERi0xLjQKMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovUGFnZXMgMiAwIFIKPj4KZW5kb2Jq"
            + "CjIgMCBvYmoKPDwKL1R5cGUgL1BhZ2VzCi9LaWRzIFszIDAgUl0KL0NvdW50IDEKPj4KZW5kb2Jq"
            + "CjMgMCBvYmoKPDwKL1R5cGUgL1BhZ2UKL1BhcmVudCAyIDAgUgovTWVkaWFCb3ggWzAgMCA2MTIg"
            + "NzkyXQovUmVzb3VyY2VzIDw8Ci9Gb250IDw8Ci9GMSA0IDAgUgo+Pgo+PgovQ29udGVudHMgNSAw"
            + "IFIKPj4KZW5kb2JqCjQgMCBvYmoKPDwKL1R5cGUgL0ZvbnQKL1N1YnR5cGUgL1R5cGUxCi9CYXNl"
            + "Rm9udCAvSGVsdmV0aWNhCj4+CmVuZG9Jago1IDAgb2JqCjw8Ci9MZW5ndGggNDQKPj4Kc3RyZWFt"
            + "CkJUCi9GMSAyNCBUZgoxMDAgNzAwIFRkCihKdXN0U2VhcmNoIFBERiBUZXN0KSBUagogRVQKZW5k"
            + "c3RyZWFtCmVuZG9iagp4cmVmCjAgNgowMDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAwMDAwMTAgMDAw"
            + "MDAgbiAKMDAwMDAwMDA2MCAwMDAwMCBuIAowMDAwMDAwMTE3IDAwMDAwIG4gCjAwMDAwMDAyMjMg"
            + "MDAwMDAgbiAKMDAwMDAwMDMxMSAwMDAwMCBuIAp0cmFpbGVyCjw8Ci9TaXplIDYKL1Jvb3QgMSAw"
            + "IFIKPj4Kc3RhcnR4cmVmCjQwNQolJUVPRgo=";

    byte[] bytes = Base64.getDecoder().decode(b64);
    Files.write(path, bytes);
  }

  private static void waitForIndexing(KnowledgeClient client, long minDocCount)
      throws InterruptedException {
    StatusResponse status = client.getStatus(TestEngineContexts.FOREGROUND);
    int maxWaitSeconds = 30;
    int stablePolls = 0;
    for (int i = 0; i < maxWaitSeconds && stablePolls < 2; i++) {
      Thread.sleep(1000);
      status = client.getStatus(TestEngineContexts.FOREGROUND);
      if (status.getCore().getQueueDepth() == 0 && status.getCore().getDocCount() >= minDocCount) {
        stablePolls++;
      } else {
        stablePolls = 0;
      }
    }
    assertTrue(
        status.getCore().getDocCount() >= minDocCount,
        "Indexing did not converge in time (queueDepth="
            + status.getCore().getQueueDepth()
            + ", docCount="
            + status.getCore().getDocCount()
            + ")");
  }

  private static SearchResponse waitForSearchHits(
      KnowledgeClient client, String query, int limit, int maxAttempts) throws InterruptedException {
    SearchResponse latest = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      latest = client.search(query, limit, PipelineConfigs.TEXT, TestEngineContexts.FOREGROUND);
      if (latest.getResultsCount() > 0) {
        return latest;
      }
      Thread.sleep(500);
    }
    return latest;
  }
}
