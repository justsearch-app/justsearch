/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.IndexingService;
import io.justsearch.ipc.ScanMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Driven by test-linux-root-volume-live.mjs, which alone owns the disposable mounts. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "JUSTSEARCH_LINUX_ROOT_VOLUME_CAMPAIGN_DIR", matches = ".+")
@Timeout(900)
final class EngineLinuxRootVolumeLiveTest {
  private final Path campaign = Path.of(System.getenv("JUSTSEARCH_LINUX_ROOT_VOLUME_CAMPAIGN_DIR"));
  private EngineTestHarness engine;
  private Path root;
  private Path control;
  private Path data;
  private int marks;

  @Test
  void absentDifferentUnknownAndReturningFilesystemsPreserveTheOriginalIndex() throws Exception {
    root = campaign.resolve("point");
    control = Files.createDirectory(campaign.resolve("control"));
    data = campaign.resolve("data");
    String previousData = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.data.dir", data.toString());
    try {
      Files.writeString(root.resolve("alpha.txt"), "marmoset lives on the original filesystem");
      Files.writeString(root.resolve("gone.txt"), "wombat will be deleted on the original");
      start();
      engine.client().addWatchedRoot("documents", root, TestEngineContexts.FOREGROUND);
      engine.client().addWatchedRoot("control", control, TestEngineContexts.FOREGROUND);
      assertTrue(engine.awaitSearchable("marmoset", 90_000));
      assertTrue(engine.awaitSearchable("wombat", 90_000));
      exerciseControl();
      report("indexed-A");

      stop();
      mount("empty");
      assertTrue(Files.isDirectory(root), "an unmounted mount point is still a real folder");
      try (var children = Files.list(root)) {
        assertEquals(0, children.count(), "the unmounted mount point is empty and listable");
      }
      start();
      assertRefused("marmoset");
      report("unmounted-preserved-A");

      stop();
      mount("B");
      start();
      assertRefused("marmoset");
      Files.writeString(root.resolve("watcher.txt"), "quokka was created on the wrong filesystem");
      exerciseControl();
      assertFalse(engine.awaitSearchable("quokka", 2_000));
      report("B-admission-and-prune-refused");

      stop();
      Files.writeString(data.resolve("index/root-volumes.properties"), "not-a-root=corrupt\n");
      start();
      assertRefused("marmoset");
      report("corrupt-ledger-preserved-A");
      stop();
      Files.deleteIfExists(data.resolve("index/root-volumes.properties"));
      start();
      assertRefused("marmoset");
      report("lost-ledger-preserved-A");

      stop();
      mount("A");
      start();
      engine.client().reindexPersistedRoots(TestEngineContexts.BACKGROUND);
      engine.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);
      exerciseControl();
      Files.writeString(root.resolve("alpha.txt"), "capybara changed the original after recovery");
      Files.delete(root.resolve("gone.txt"));
      engine.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);
      assertTrue(engine.awaitSearchable("capybara", 90_000), "A recovers admission");
      assertTrue(engine.awaitNotSearchable("wombat", 90_000), "A recovers genuine deletion");
      report("A-recovered-indexing-and-pruning");

      stop();
      mount("tmpfs");
      start();
      assertRefused("capybara");
      report("known-identity-to-tmpfs-preserved-A");
      stop();
      mount("empty");
      start();
      assertRefused("capybara");
      report("tmpfs-unmounted-known-preserved-A");
      stop();
      mount("A");
      start();
      engine.client().reindexPersistedRoots(TestEngineContexts.BACKGROUND);
      engine.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);
      exerciseControl();
      assertTrue(engine.awaitSearchable("capybara", 10_000));
      assertTrue(rootState().walkError() == null, "original root recovers cleanly");
      report("A-returned-cleanly");

      // An initially UUID-less filesystem follows the same no-serial contract as Windows:
      // present files are watched and indexed, but missing volume evidence cannot prove deletion.
      stop();
      mount("tmpfs");
      data = campaign.resolve("initially-unknown-data");
      System.setProperty("justsearch.data.dir", data.toString());
      start();
      engine.client().addWatchedRoot("documents", root, TestEngineContexts.FOREGROUND);
      engine.client().addWatchedRoot("control", control, TestEngineContexts.FOREGROUND);
      assertTrue(engine.awaitSearchable("pangolin", 90_000));
      assertTrue(engine.awaitSearchable("okapi", 90_000));
      var volumes = new java.util.Properties();
      try (var input = Files.newInputStream(data.resolve("index/root-volumes.properties"))) {
        volumes.load(input);
      }
      assertFalse(volumes.containsKey(root.toString()), "no volume identity is invented for tmpfs");
      Files.delete(root.resolve("alpha.txt"));
      var unproven = engine.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);
      assertTrue(unproven.getDeleteDetectionUnverified());
      assertTrue(engine.awaitSearchable("pangolin", 10_000), "unknown volume preserves indexed content");
      exerciseControl();
      report("initially-unknown-tmpfs-indexed-and-preserved");

      stop();
      start();
      engine.client().reindexPersistedRoots(TestEngineContexts.BACKGROUND);
      Files.writeString(root.resolve("extra.txt"), "ibis is a real change on the UUID-less filesystem");
      engine.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);
      assertTrue(engine.awaitSearchable("ibis", 90_000), "UUID-less root works after restart");
      assertTrue(engine.awaitSearchable("pangolin", 10_000),
          "unknown volume still preserves indexed content after restart");
      assertTrue(rootState().walkError() == null);
      report("initially-unknown-tmpfs-restarted-cleanly");
    } finally {
      stop();
      if (previousData == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", previousData);
    }
  }

  private void start() throws Exception {
    engine = EngineTestHarness.start(data);
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (System.nanoTime() < deadline) {
      var response = engine.client().syncDirectory(control.toString(), false, TestEngineContexts.BACKGROUND);
      if (response != null && (response.getError().isEmpty()
          || response.getError().startsWith("ROOT_NOT_DIRECTORY"))) return;
      Thread.sleep(100);
    }
    fail("Engine index half did not become ready");
  }

  private void stop() {
    if (engine != null) {
      engine.close();
      engine = null;
    }
  }

  private void assertRefused(String retained) throws Exception {
    engine.client().reindexPersistedRoots(TestEngineContexts.BACKGROUND);
    engine.client().syncDirectory(root.toString(), false, TestEngineContexts.BACKGROUND);
    var walked = engine.client().scanRoot(root.toString(), null, ScanMode.SCAN_MODE_INITIAL,
        List.of(), ignored -> {}, TestEngineContexts.FOREGROUND);
    assertEquals(0, walked.getFilesAdmitted(), "unproven filesystem admits no files");
    assertFalse(walked.getTerminalReasonCode().isEmpty(), "walk reports the refused root");
    exerciseControl();
    assertTrue(engine.awaitSearchable(retained, 10_000), "original content remains searchable");
    assertFalse(engine.awaitSearchable("pangolin", 1_000));
    assertFalse(engine.awaitSearchable("okapi", 1_000));
    assertTrue(engine.awaitSearchable("wombat", 1_000) || retained.equals("capybara"),
        "original missing file is never pruned on uncertainty");
  }

  private void exerciseControl() throws Exception {
    String marker = "axolotl" + (char) ('a' + marks++);
    Files.writeString(control.resolve(marker + ".txt"), marker + " proves the pipeline ran");
    engine.client().syncDirectory(control.toString(), false, TestEngineContexts.BACKGROUND);
    assertTrue(engine.awaitSearchable(marker, 90_000), "control root indexes while target is refused");
  }

  private IndexingService.WatchedRoot rootState() {
    return engine.client().getWatchedRoots(TestEngineContexts.FOREGROUND).stream()
        .filter(state -> root.equals(state.path())).findFirst().orElseThrow();
  }

  private void mount(String stage) throws Exception {
    Files.writeString(campaign.resolve("request"), stage);
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      Path error = campaign.resolve("mount-error");
      if (Files.exists(error)) fail(Files.readString(error));
      Path ack = campaign.resolve("ack");
      if (Files.exists(ack) && Files.readString(ack).equals(stage)) {
        Files.delete(ack);
        return;
      }
      Thread.sleep(50);
    }
    fail("Mount driver did not acknowledge " + stage);
  }

  private void report(String stage) throws Exception {
    Files.writeString(campaign.resolve(stage + ".passed"), stage);
    System.out.println("LINUX_ROOT_VOLUME " + stage + " PASS");
  }
}
