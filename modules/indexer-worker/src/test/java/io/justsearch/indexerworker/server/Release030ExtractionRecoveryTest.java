/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.ExtractionSandbox;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** U9: actual sandbox calls, not a queue-depth approximation of work saved. */
@Timeout(120)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030ExtractionRecoveryTest {
  @Test
  void recoveryWalkExtractsChangedFilesAndSkipsUnchangedReleaseFiles(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path added = Files.writeString(root.resolve("roots/alpha/during-upgrade.txt"), "ochrearrival");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models;
        var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        assertEquals("IDLE", state(root).migration_state());
        // Hold scheduled recovery until instrumentation is installed on the actual writer loop.
        var scheduled = KnowledgeServer.class.getDeclaredField("release030RecoveryTask");
        scheduled.setAccessible(true);
        ((java.util.concurrent.Future<?>) scheduled.get(server)).cancel(false);
        activate(server);
        var counts = countExtractions(server);
        var recover = KnowledgeServer.class.getDeclaredMethod("recoverRelease030RootsSafely");
        recover.setAccessible(true);
        recover.invoke(server);
        await(() -> search(server, "ochrearrival").contains(key(added))
            && count(root, "state='PENDING' AND retry_after IS NULL OR state='PROCESSING'") == 0,
            "new and changed files finish through the recovery walk");
        assertTrue(counts.changed.get() > 0, "the probe sees real extraction calls for admitted new work");
        assertEquals(0, counts.unchanged.get(), "unchanged release documents need no extraction");
        assertTrue(count(root, "last_reason_code='UNCHANGED'") >= 20, "the release's unchanged fast path ran");
        System.out.println("Release030 extraction calls: changed=" + counts.changed.get()
            + " unchanged=" + counts.unchanged.get());
      }
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  /** Also used by the on-demand scale probe; constant memory even for 700k queue rows. */
  static Counts countExtractions(KnowledgeServer server) throws Exception {
    var services = (DefaultWorkerAppServices) server.appServices();
    // Swap the delegating probe without draining the producer: a scale batch may legitimately
    // exceed the cutover timeout. In-flight calls keep their original delegate; later calls are counted.
    Object loop = field(services, "indexingLoop");
    Object extractor = field(loop, "contentExtractor");
    var sandboxField = extractor.getClass().getDeclaredField("sandbox");
    sandboxField.setAccessible(true);
    var original = (ExtractionSandbox) sandboxField.get(extractor);
    assertTrue(original != null, "production loop uses its configured sandbox");
    var counts = new Counts();
    sandboxField.set(extractor, new ExtractionSandbox() {
      @Override public ExtractionArtifact extract(Path file)
          throws IOException, ContentExtractor.ExtractionException {
        String name = file.getFileName().toString();
        String path = file.toString().replace('\\', '/');
        boolean unchanged = name.startsWith("file-") && !path.endsWith("/alpha/file-00.txt");
        if (path.contains("/alpha/scale/")) {
          unchanged = Integer.parseInt(name.substring(0, name.length() - 4)) < 200_001;
        }
        (unchanged ? counts.unchanged : counts.changed).incrementAndGet();
        return original.extract(file);
      }
      @Override public TikaExtractionPolicy policy() { return original.policy(); }
      @Override public void close() { original.close(); }
    });
    return counts;
  }

  static final class Counts {
    final AtomicLong unchanged = new AtomicLong();
    final AtomicLong changed = new AtomicLong();
  }

  static Object field(Object owner, String name) throws Exception {
    var declared = owner.getClass().getDeclaredField(name);
    declared.setAccessible(true);
    return declared.get(owner);
  }
}
