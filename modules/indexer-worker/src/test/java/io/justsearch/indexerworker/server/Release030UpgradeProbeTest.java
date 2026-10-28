/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Installed-run fixture preparation and bounded, opt-in #739 scale measurement. */
@Timeout(180)
final class Release030UpgradeProbeTest {
  @Test
  void interruptedMigrationFixturesKeepTheirServingIndexAndJobs(@TempDir Path temporary) throws Exception {
    for (String name : List.of("migrating", "switching")) {
      Path root = Release030Fixture.unpack(name, temporary.resolve(name));
      Path serving = Release030CompatibilityTest.generation(root);
      var metadata = Release030CompatibilityTest.readMetadata(serving);
      long jobs = count(root, "1=1");
      ConfigStore previous = ConfigStore.globalOrNull();
      var installed = configure(root);
      String disposition;
      try {
        var models = new ReleasedModelIdentities(root);
        try (models; var executors = new TestEngineExecutors()) {
          try (var server = boot(root, executors)) {
            disposition = "started, runtime=" + server.lifecycleManagerForTests().getClass().getSimpleName()
              + ", state=" + state(root).migration_state()
              + ", old_search_matches=" + search(server, "nebula").size();
          } catch (java.io.IOException | RuntimeException refused) {
            disposition = refused.getClass().getSimpleName() + ": " + refused.getMessage();
          }
          assertTrue(Files.isDirectory(serving), "interrupted serving generation is retained");
          assertEquals(serving, Release030CompatibilityTest.generation(root), "the serving generation identity survives");
          var oldInputs = new tools.jackson.databind.ObjectMapper().readTree(metadata.get("index_fingerprint_inputs"));
          var newInputs = new tools.jackson.databind.ObjectMapper().readTree(
              Release030CompatibilityTest.readMetadata(serving).get("index_fingerprint_inputs"));
          for (String identity : List.of("analyzer_fp", "embedding_model_sha256", "splade_model_sha256", "ner_model_sha256")) {
            assertEquals(oldInputs.get(identity), newInputs.get(identity), "compatible writes preserve " + identity);
          }
          assertTrue(count(root, "1=1") >= jobs, "no legacy queue row is deleted by this change");
          System.out.println("Release030 " + name + ": " + disposition
              + ", jobs=" + count(root, "1=1") + ", queued="
              + count(root, "state IN ('PENDING','PROCESSING')"));
        }
      } finally {
        ConfigStore.restoreGlobal(installed, previous);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[1]);
    if (args[0].equals("unpack")) {
      Release030Fixture.unpack("small", root);
      System.out.println("Relocated authentic release folder to " + root);
      return;
    }
    // Keep generated sources at their indexed paths in build/, while the Engine's home/data
    // live under TMPDIR. Move once and reuse; never regenerate or rewrite the 700k path identities.
    Path sources = root;
    root = Path.of(System.getenv("JUSTSEARCH_DATA_DIR")).getParent();
    Files.createDirectories(root);
    if (Files.exists(sources.resolve("data"))) {
      Files.move(sources.resolve("data"), root.resolve("data"));
      Files.move(sources.resolve("home"), root.resolve("home"));
    }
    long started = System.nanoTime();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors();
        var server = boot(root, sources, executors)) {
        assertEquals("IDLE", state(root).migration_state(), "#739 scale must never start migration");
        var task = KnowledgeServer.class.getDeclaredField("release030RecoveryTask");
        task.setAccessible(true);
        if (task.get(server) instanceof java.util.concurrent.Future<?> scheduled) scheduled.cancel(false);
        assertEquals(0, count(root, "state IN ('PENDING','PROCESSING') AND ingestion_root IS NULL"),
            "all 700k queued paths have durable boundaries before processing");
        activate(server);
        var extractions = Release030ExtractionRecoveryTest.countExtractions(server);
        var recover = KnowledgeServer.class.getDeclaredMethod("recoverRelease030RootsSafely");
        recover.setAccessible(true);
        Thread recovery = Thread.startVirtualThread(() -> {
          try { recover.invoke(server); }
          catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[2]));
        long remaining;
        do {
          remaining = count(root, "state='PROCESSING' OR state='PENDING' AND retry_after IS NULL");
          System.out.println("Scale seconds=" + (System.nanoTime() - started) / 1_000_000_000.0
              + " ready_or_processing=" + remaining + " unchanged_extractions=" + extractions.unchanged.get()
              + " changed_extractions=" + extractions.changed.get()
              + " recovery_pending=" + Files.exists(root.resolve("data/upgrade-030-recovery.properties")));
          if (remaining == 0 && !recovery.isAlive()
              && !Files.exists(root.resolve("data/upgrade-030-recovery.properties"))) break;
          Thread.sleep(10_000);
        } while (System.nanoTime() < deadline);
        assertEquals(0, count(root, "state='FAILED' AND last_reason_code='PARSER_FAILED'"),
            "the measured new paths must not fail parsing");
        assertEquals(0, extractions.unchanged.get(), "scale recovery never extracts unchanged indexed files");
        assertTrue(extractions.changed.get() > 0);
        boolean complete = remaining == 0 && !recovery.isAlive()
            && !Files.exists(root.resolve("data/upgrade-030-recovery.properties"));
        System.out.println("Scale drain " + (complete ? "complete" : "time-bounded")
            + "; ready/processing=" + remaining);
        recovery.interrupt();
        recovery.join(TimeUnit.SECONDS.toMillis(30));
        assertTrue(!recovery.isAlive(), "owned recovery walk exits before resource closure");
      }
    } finally {
      ConfigStore.restoreGlobal(installed, null);
    }
  }
}
