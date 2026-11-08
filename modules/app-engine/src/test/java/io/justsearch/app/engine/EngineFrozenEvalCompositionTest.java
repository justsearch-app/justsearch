/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.ReadOnlyRuntime;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.indexerworker.disambiguation.EntityClusterStore;
import io.justsearch.ipc.PipelineConfigs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Full production Engine composition over an owned, normally ingested tiny index; no models. */
@Timeout(120)
final class EngineFrozenEvalCompositionTest {
  @Test
  void frozenReopenPublishesRealClientWithoutAttachingRecordedProducers(@TempDir Path directory)
      throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    Map<String, String> properties = new HashMap<>();
    Path data = directory.resolve("data");
    Path models = Files.createDirectories(directory.resolve("empty-models"));
    Path source = Files.writeString(directory.resolve("retained.txt"),
        "quokkafrozen retained preview witness from the committed tiny fixture");
    try {
      property(properties, "justsearch.eval.mode", "true");
      property(properties, "justsearch.eval.frozen", "false");
      property(properties, "justsearch.ai.disabled", "true");
      property(properties, "justsearch.llm.enabled", "false");
      property(properties, "justsearch.models.dir", models.toString());
      property(properties, "ai.djl.offline", "true");
      for (var role : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.SPLADE_ENABLED,
          EnvRegistry.NER_ENABLED, EnvRegistry.RERANK_ENABLED, EnvRegistry.RERANK_CHUNKS_ENABLED,
          EnvRegistry.CITATION_SCORER_ENABLED, EnvRegistry.BGE_M3_ENABLED}) {
        property(properties, role.sysProp(), "false");
      }
      Files.createDirectories(data);
      // These stores are created by their real owners, before the frozen physical lifetime.
      try (var operations = new SqliteOperationStore(data.resolve("operations.db"));
          var clusters = new EntityClusterStore(data.resolve("entity-clusters.db"))) {
        clusters.open();
      }
      String id;
      String generation;
      try (var ordinary = EngineTestHarness.start(data)) {
        assertEquals(1, ordinary.client().submitBatch(List.of(source),
            TestEngineContexts.FOREGROUND).getAcceptedCount());
        assertTrue(ordinary.awaitIndexed(1, 45_000), "ordinary producer must index the fixture");
        assertTrue(ordinary.awaitSearchable("quokkafrozen", 15_000));
        id = ordinary.client().search("quokkafrozen", 1, PipelineConfigs.TEXT,
            TestEngineContexts.FOREGROUND).getResults(0).getId();
        generation = ordinary.status().getMigration().getActiveGenerationId();
      }
      Files.delete(source); // Reads must use retained content, rather than the original file.
      Map<String, String> retained = payload(data.resolve("index"));
      property(properties, "justsearch.eval.frozen", "true");
      try (var frozen = EngineTestHarness.start(data)) {
        var client = frozen.client();
        assertNotNull(client, "EngineRoot.start must complete genuine client publication");
        try (var serving = frozen.captureServingView()) {
          assertInstanceOf(ReadOnlyRuntime.class, serving.searchRuntime());
          assertSame(serving.searchRuntime(), serving.ingestRuntime());
        }
        var status = frozen.status();
        assertEquals(1, status.getCore().getDocCount());
        assertEquals(generation, status.getMigration().getActiveGenerationId());
        assertEquals(0, status.getCore().getQueueDepth());
        assertEquals(id, client.search("quokkafrozen", 1, PipelineConfigs.TEXT,
            TestEngineContexts.FOREGROUND).getResults(0).getId());
        assertEquals(List.of(id), client.listAllDocumentIds(0, 100, true,
            TestEngineContexts.FOREGROUND).getDocIdsList());
        var document = client.fetchDocuments(List.of(id), TestEngineContexts.FOREGROUND)
            .getDocuments(0);
        assertTrue(document.getFound());
        assertTrue(document.getContent().contains("retained preview witness"));
        assertFalse(client.getCommitMetadata(TestEngineContexts.FOREGROUND).isEmpty());
        assertEquals(1, client.getDebugWorkerState(TestEngineContexts.FOREGROUND).docCount());
        assertThrows(RuntimeException.class, () -> client.submitBatch(List.of(source),
            TestEngineContexts.FOREGROUND));
        assertEquals(retained, payload(data.resolve("index")));
      }
      assertEquals(retained, payload(data.resolve("index")), "orderly stop retains index payload");
    } finally {
      properties.forEach((key, value) -> {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
      });
      ConfigStore.restoreGlobal(ConfigStore.global(), previous);
    }
  }

  private static void property(Map<String, String> previous, String key, String value) {
    if (!previous.containsKey(key)) previous.put(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private static Map<String, String> payload(Path root) throws Exception {
    Map<String, String> result = new TreeMap<>();
    try (var paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        result.put(root.relativize(path).toString(), HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
      }
    }
    return result;
  }
}
