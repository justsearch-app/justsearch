/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeBuilder;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GenerationEmbeddingAttestationTest {
  @Test
  void aSelectedModelCannotStampAnUnattachedEmptyCandidateOrBorrowTheSourceStamp(@TempDir Path dir)
      throws Exception {
    var previous = ConfigStore.globalOrNull();
    WorkerBootFixture.publishConfig(dir, dir.resolve("index"), "BLUE_GREEN_MIGRATE");
    try (var executors = new TestEngineExecutors()) {
      var manager = new IndexGenerationManager(dir.resolve("index"));
      var initial = manager.initializeOrLoad();
      String key = "01994180-0000-7000-8000-000000000208";
      String source = initial.state().active_generation();
      String building = manager.startRecordedMigration(key, "installer_model_activation", "a".repeat(64), source,
          List.of()).building_generation();
      Path model = Files.writeString(dir.resolve("model.onnx"), "model identity without execution");
      String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(Files.readAllBytes(model)));
      manager.bindRecordedModels(key, "installer_model_activation", "a".repeat(64),
          Map.of("embedding", new IndexGenerationManager.ModelArtifact(model.toString(), sha)), "splade", 768);
      var config = ConfigStore.global().get();
      var server = new KnowledgeServer(executors, io.justsearch.indexerworker.WorkerConfig.load(config), null);
      field(server, "startupConfiguration", config);
      field(server, "indexGenerationManager", manager);
      field(server, "activeIndexPath", manager.resolveGenerationPathStrict(source));
      var method = KnowledgeServer.class.getDeclaredMethod("buildIndexRuntime", Path.class, Supplier.class);
      method.setAccessible(true);
      var builder = (LuceneRuntimeBuilder) method.invoke(server, manager.resolveGenerationPathStrict(building),
          (Supplier<Optional<String>>) () -> Optional.of(sha)); // Earned A evidence, same SHA as B.
      try (var runtime = builder.withBuildState(LuceneRuntimeTypes.BuildState.BUILDING).open()) {
        runtime.commitOps().commitWithBuildState(LuceneRuntimeTypes.BuildState.COMPLETE, CommitReason.MIGRATION_CUTOVER);
        assertEquals(0L, runtime.indexCountOps().docCountOrThrow());
        assertFalse(runtime.latestCommitUserDataBestEffort().containsKey(EmbeddingCompatibilityController.COMMIT_META_KEY),
            "a manifest's model identity and A's stamp are not B's embedding success evidence");
      }
      server.close();
    } finally {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  private static void field(Object owner, String name, Object value) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }
}
