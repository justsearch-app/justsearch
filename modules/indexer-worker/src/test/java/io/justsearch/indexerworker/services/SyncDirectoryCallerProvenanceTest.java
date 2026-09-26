/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.ipc.SyncDirectoryRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SyncDirectoryCallerProvenanceTest {
  @TempDir Path tempDir;

  @Test
  void explicitAgentSyncOverwritesEarlierCallerAttribution() throws Exception {
    assertSyncAttribution(
        new JobQueue.EnqueueProvenance("user", "BUTTON"),
        context(
            EngineContext.ClientKind.INTERNAL,
            "agent-client",
            "UNTRUSTED",
            new JobQueue.EnqueueProvenance("agent", "AGENT_LOOP")),
        new JobQueue.EnqueueProvenance("agent", "AGENT_LOOP"));
  }

  @Test
  void internalSystemMaintenancePreservesEarlierAgentAttribution() throws Exception {
    JobQueue.EnqueueProvenance agent =
        new JobQueue.EnqueueProvenance("agent", "AGENT_LOOP");
    assertSyncAttribution(
        agent,
        context(
            EngineContext.ClientKind.INTERNAL,
            "index-maintenance",
            "TRUSTED",
            new JobQueue.EnqueueProvenance("system", "SYSTEM_INTERNAL")),
        agent);
  }

  @Test
  void cliSystemInternalSyncRemainsExplicitSystemAdmission() throws Exception {
    assertSyncAttribution(
        new JobQueue.EnqueueProvenance("agent", "AGENT_LOOP"),
        context(
            EngineContext.ClientKind.CLI,
            "cli-client",
            "TRUSTED",
            new JobQueue.EnqueueProvenance("system", "SYSTEM_INTERNAL")),
        new JobQueue.EnqueueProvenance("system", "SYSTEM_INTERNAL"));
  }

  private void assertSyncAttribution(
      JobQueue.EnqueueProvenance previous,
      CallContext caller,
      JobQueue.EnqueueProvenance expected)
      throws Exception {
    String caseId = Long.toString(System.nanoTime());
    Path caseDir = Files.createDirectories(tempDir.resolve("case-" + caseId));
    Path document = Files.writeString(caseDir.resolve("document.txt"), "content");
    try (SqliteJobQueue queue = new SqliteJobQueue(tempDir.resolve("jobs-" + caseId + ".db"))) {
      queue.open();
      assertEquals(
          1,
          queue.enqueueEntries(
              List.of(new JobQueue.EnqueueEntry(document, Files.size(document), previous))));
      WorkerIngestService service =
          new WorkerIngestService(
              queue,
              null,
              null,
              IndexingPacing.unthrottled(),
              caseDir.resolve("index-base"),
              caseDir.resolve("index"),
              mock(RunningRuntime.class),
              null,
              null,
              0L);

      var response =
          service.syncDirectory(
              SyncDirectoryRequest.newBuilder()
                  .setRootPath(caseDir.toString())
                  .setForce(true)
                  .build(),
              caller);

      assertTrue(response.getError().isBlank(), response.getError());
      List<JobQueue.IndexJob> claimed = queue.pollPending(10);
      JobQueue.IndexJob documentJob =
          claimed.stream()
              .filter(job -> job.path().equals(document))
              .findFirst()
              .orElseThrow();
      assertEquals(expected, documentJob.provenance());
    }
  }

  private static CallContext context(
      EngineContext.ClientKind clientKind,
      String clientId,
      String sourceTier,
      JobQueue.EnqueueProvenance provenance) {
    return new CallContext(
        null,
        null,
        CallContext.CancelSignal.NEVER,
        new EngineContext(
            clientKind,
            clientId,
            Optional.of("sync-session"),
            Optional.empty(),
            sourceTier,
            provenance.transport(),
            EngineContext.Survival.DURABLE,
            EngineContext.Urgency.BACKGROUND),
        provenance, io.justsearch.core.execution.EngineTaskLifetime.NONE);
  }
}
