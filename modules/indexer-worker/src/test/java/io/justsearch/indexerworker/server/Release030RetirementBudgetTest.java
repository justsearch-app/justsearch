/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.CallContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** U13 preservation: real native boot, configured failure limits, explicit cutover, then restart. */
@Timeout(240)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementBudgetTest {
  @ParameterizedTest
  @CsvSource({"1,2,false,false", "1,2,true,false", "1,1,false,true", "1,1,true,true",
      "-1,2,false,true", "-1,2,true,true", "0,0,false,true"})
  void nativeFailureBudgetAndForceSwitchingKeepTheirMeaning(int limit, int failures,
      boolean force, boolean promotes, @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("migrating", temporary);
    var original = state(root);
    Path sentinel = Release030RetirementSafetyTest.buildingSentinel(root);
    String owner = Files.readString(sentinel);
    Files.writeString(sentinel, "unverified candidate owner");
    Files.delete(root.resolve("roots/beta/file-01.txt"));
    ConfigStore previous = ConfigStore.globalOrNull();
    WorkerBootFixture.publishConfig(root.resolve("data"), root.resolve("data/index/default"),
        "BLUE_GREEN_MIGRATE", Map.of("justsearch.ai.disabled", "true", "justsearch.ai.embed.enabled", "false",
            "justsearch.splade.enabled", "false", "justsearch.ner.enabled", "false",
            "index.migration.cutover.max_failed_jobs", Integer.toString(limit)));
    var installed = ConfigStore.global();
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          Files.writeString(sentinel, owner);
          assertEquals("MIGRATING", state(root).migration_state(), "uncertified builds retain native cutover");
          var queue = Release030RetirementSafetyTest.queue(server);
          for (int failure = 0; failure < failures; failure++) {
            Path path = Files.writeString(root.resolve("failed-" + failure + ".bin"), "terminal parser input");
            assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(path).withinRoot(path)), null));
            queue.markFailed(path, IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED,
                IngestionReasonCodes.PARSER_FAILED, IngestionRetryPolicy.NONE, "terminal parser result"));
          }
          assertEquals(failures, queue.nativeCutoverCountsStrict().failedCount() - queue.unownedFailureCount());
          Release030ExactFileRecoveryTest.activateNative(server);
          if (force) assertTrue(server.appServices().ingestService().requestCutover(
              io.justsearch.ipc.MigrationCutoverRequest.newBuilder().setForceSwitching(true).build(),
              CallContext.none()).getAccepted());
          assertTrue(server.appServices().ingestService().resumeMigration(
              io.justsearch.ipc.MigrationResumeRequest.getDefaultInstance(), CallContext.none()).getAccepted());
          await(() -> (promotes ? "IDLE" : "FAILED").equals(state(root).migration_state()),
              "configured native budget decides promotion without an implicit override", 100);
          assertEquals(promotes ? original.building_generation() : original.active_generation(),
              state(root).active_generation());
          assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
          assertTrue(search(server, "copper").contains(key(root.resolve("roots/alpha/file-00.txt"))));
          assertEquals(!promotes, search(server, "nebula").contains(key(root.resolve("roots/beta/file-01.txt"))));
          if (promotes) {
            assertTrue(search(server, "copper").contains(key(root.resolve("roots/alpha/pending.txt"))));
          }
        }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          assertEquals(promotes ? original.building_generation() : original.active_generation(),
              state(root).active_generation());
          // Base resumes original independent debt in MIGRATING before the readiness latch.
          assertEquals(promotes ? "IDLE" : "MIGRATING", state(root).migration_state());
          assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
          assertTrue(search(server, "copper").contains(key(root.resolve("roots/alpha/file-00.txt"))));
          assertEquals(!promotes, search(server, "nebula").contains(key(root.resolve("roots/beta/file-01.txt"))));
        }
      }
    } finally {
      if (Files.exists(sentinel)) Files.writeString(sentinel, owner);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }
}
