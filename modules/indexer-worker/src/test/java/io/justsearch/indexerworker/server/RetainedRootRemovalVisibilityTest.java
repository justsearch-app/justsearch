/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** S35.5 probe: a retained generation is exercised through the actual Worker boot path. */
@Timeout(120)
final class RetainedRootRemovalVisibilityTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retainedGenerationCannotResurrectRemovedSearchResults(boolean damagedPointer) throws Exception {
    ConfigStore prior = ConfigStore.globalOrNull();
    KnowledgeServer server = null;
    var workerExecutors = new io.justsearch.core.execution.TestEngineExecutors();
    try {
      var layout = WorkerBootFixture.layout(temp);
      WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");
      Path folder = Files.createDirectory(temp.resolve("folder"));
      Path document = Files.writeString(folder.resolve("notes.txt"), "quokka");
      String key = PathNormalizer.normalizeKey(document);
      WorkerBootFixture.seedDocument(layout.activePath(), null, key, "retained-root", "quokka");
      var migration = layout.genManager().startMigration("retained-removal-probe");
      Path successor = layout.genManager().resolveGenerationPathStrict(migration.building_generation());
      WorkerBootFixture.seedDocument(successor, null, key, "retained-root", "quokka");
      layout.genManager().promoteBuildingGenerationToActive();
      boolean completed;
      try (var queue = new SqliteJobQueue(layout.dataDir().resolve("jobs.db"));
          RunningRuntime writer = IndexSchema.fromCatalog(WorkerBootFixture.productionCatalog())
              .atPath(successor).withExecutorRegistrations(testLuceneExecutors()).open()) {
        queue.open();
        var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
            layout.indexBase(), successor, writer, writer, null, 0L);
        var response = service.deleteByPath(DeleteByPathRequest.newBuilder()
            .setPath(folder.toString()).build(), CallContext.none());
        completed = response.getError().isEmpty() && !response.getBuffered()
            && response.getDeletedJobs() >= 0;
        writer.commitOps().maybeRefreshBlocking();
        if (completed) assertEquals(0, writer.textQueryOps().searchText("quokka", 10, null).totalHits());
      }
      assertEquals("quokka", WorkerBootFixture.readDocumentField(layout.activePath(), key,
          io.justsearch.indexing.SchemaFields.CONTENT), "precondition: retained predecessor still contains the document");
      // A truthful incomplete answer is allowed. Once the retained generation is released, retry
      // must finish, and that completion has the same recovery guarantee as immediate completion.
      if (!completed) {
        layout.genManager().retirePreviousGeneration(migration.building_generation(), migration.active_generation());
        try (var queue = new SqliteJobQueue(layout.dataDir().resolve("jobs.db"));
            var writer = IndexSchema.fromCatalog(WorkerBootFixture.productionCatalog())
                .atPath(successor).withExecutorRegistrations(testLuceneExecutors()).open()) {
          queue.open();
          var service = new WorkerIngestService(queue, null, null, IndexingPacing.unthrottled(),
              layout.indexBase(), successor, writer, writer, null, 0L);
          var retry = service.deleteByPath(DeleteByPathRequest.newBuilder()
              .setPath(folder.toString()).build(), CallContext.none());
          assertEquals("", retry.getError(), "removal can be retried once the predecessor retires");
          assertFalse(retry.getBuffered());
          assertTrue(retry.getDeletedJobs() >= 0);
        }
      }
      Files.writeString(layout.dataDir().resolve("watched_roots.json"), "{\"schemaVersion\":1,\"roots\":[]}");
      if (damagedPointer) Files.writeString(layout.indexBase().resolve("state.json"), "{forced torn pointer");
      server = new KnowledgeServer(workerExecutors,
          WorkerBootFixture.workerConfig(layout.dataDir()));
      server.bindBootRootBindings(List.of());
      try {
        server.start();
      } catch (IOException refusal) {
        if (!damagedPointer) throw refusal;
        String reason = exceptionMessages(refusal);
        assertTrue(reason.contains("generation_previous_unproven")
                || reason.contains("Authoritative generation") || reason.contains("generation state"),
            "only a generation-ownership refusal establishes safety: " + reason);
        System.out.println("S35.5 damaged-pointer boot refused: " + reason);
        return;
      }
      server.releaseModelReadyLatchForTests();
      var response = server.appServices().searchService().search(SearchRequest.newBuilder()
          .setQuery("quokka").setMode(SearchMode.SEARCH_MODE_TEXT).setLimit(10).build(), CallContext.none());
      assertTrue(response.getResultsList().isEmpty(),
          "a real boot must not serve removed documents from a retained predecessor; damagedPointer=" + damagedPointer);
      System.out.println("S35.5 retained-generation boot searched without resurrection; damagedPointer=" + damagedPointer);
    } finally {
      if (server != null) server.close();
      workerExecutors.close();
      TestResolvedConfigHelper.restoreGlobal(prior);
    }
  }

  private static String exceptionMessages(Throwable failure) {
    StringBuilder result = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      result.append(current.getMessage()).append(' ');
    }
    return result.toString();
  }
}
