/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.configuration.excludes.LiteralExclusion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Actual Forget and Settings owners; no real-volume identity required for outside-root folders. */
@Timeout(90)
final class EngineForgetHonestyTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  private String explanation(OperationResult result) {
    assertTrue(result.success(), result.message());
    return JSON.valueToTree(result.structuredData().get("forget")).path("explanation").asString();
  }

  @Test
  void neverIndexedAndAlreadyForgottenTargetsDescribeAbsenceWithoutClaimingARemoval() throws Exception {
    Path target = Files.createDirectory(directory.resolve("empty-folder"));
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of())) {
      String first = explanation(f.forget(target, "FOLDER"));
      assertTrue(first.contains("no longer in the search index"), first);
      assertFalse(first.contains("Removal from the search index completed"));
      String second = explanation(f.forget(target, "FOLDER"));
      assertTrue(second.contains("no longer in the search index"), second);
      assertTrue(Files.isDirectory(target));
    }
  }

  @Test
  void repeatedExactTargetHasOneEntryAndRemovingItReleasesTheEffectivePolicy() throws Exception {
    Path target = Files.createDirectory(directory.resolve("folder"));
    Path child = Files.writeString(target.resolve("child.txt"), "original bytes survive");
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of())) {
      f.seed(child, "indexed bytes");
      f.forget(target, "FOLDER");
      f.forget(target, "FOLDER");
      f.forget(target.resolve("."), "FOLDER");
      var entries = f.settings.inspect().settings().getExcludePatterns();
      assertEquals(1, entries.size(), "same exact target must have only one removable keep-out entry");
      var entry = LiteralExclusion.fromEntry(entries.getFirst());
      assertEquals(target.toString(), entry.literal().path());
      assertEquals(LiteralExclusion.Scope.FOLDER, entry.literal().scope());
      var saved = f.settings.inspect();
      var request = JSON.readValue(JSON.writeValueAsString(java.util.Map.of(
          "ui", java.util.Map.of("excludePatterns", List.of()), "witness", saved.witness(),
          "operationKey", io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC()))), SettingsV2.class);
      var result = new SettingsServiceImpl(f.settings, f.attempts).applyPublic(request, null, EngineForgetInProcessTest.webview());
      assertTrue(result.response().success(), result.response().message());
      assertTrue(f.settings.inspect().settings().getExcludePatterns().isEmpty());
      assertFalse(io.justsearch.configuration.excludes.ExclusionPatterns.fromRawJsonStrict(
          f.config.currentSnapshot().ui().excludePatterns(), false).isExcludedPath(target, child));
      assertEquals("original bytes survive", Files.readString(child));
    }
  }
  @Test
  void removingTheOnlyRepeatedFileEntryAllowsTheExistingForceReindexPathToAdmitItAgain() throws Exception {
    Path watched = Files.createDirectory(directory.resolve("watched"));
    Path target = Files.writeString(watched.resolve("target.txt"), "original target bytes");
    Path sibling = Files.writeString(watched.resolve("sibling.txt"), "positive reindex control");
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of(watched))) {
      f.client.indexingHold().pause(null, "USER_PAUSED");
      f.seed(target, "old indexed source");
      f.forget(target, "FILE"); f.forget(target, "FILE");
      assertEquals(1, f.settings.inspect().settings().getExcludePatterns().size());
      f.client.reindexWatchedRoots(true, EngineForgetInProcessTest.webview());
      long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while (f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(sibling), false) && System.nanoTime() < until) Thread.sleep(10);
      assertFalse(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(sibling), false),
          "SETUP: existing Reindex must admit the allowed sibling through the real root scan");
      assertTrue(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(target), false));
      var saved = f.settings.inspect();
      var request = JSON.readValue(JSON.writeValueAsString(java.util.Map.of(
          "ui", java.util.Map.of("excludePatterns", List.of()), "witness", saved.witness(),
          "operationKey", io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC()))), SettingsV2.class);
      var result = new SettingsServiceImpl(f.settings, f.attempts).applyPublic(request, null, EngineForgetInProcessTest.webview());
      assertTrue(result.response().success(), result.response().message());
      f.client.reindexWatchedRoots(true, EngineForgetInProcessTest.webview());
      until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while (f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(target), false) && System.nanoTime() < until) Thread.sleep(10);
      assertFalse(f.queue.forgetJobsAbsent(EngineForgetInProcessTest.key(target), false),
          "removing the one Settings entry must let the actual Reindex enqueue the exact target");
      assertEquals("original target bytes", Files.readString(target));
    }
  }

  @Test
  void watchedFoldersAndProtectedSourcesRefuseWithTheirSpecificReasonsBeforeAnyConsent() throws Exception {
    Path watched = Files.createDirectories(directory.resolve("outer/watched"));
    try (var f = new EngineForgetInProcessTest.Fixture(directory.resolve("process"), List.of(watched))) {
      var context = EngineForgetInProcessTest.webview();
      var provenance = io.justsearch.app.services.intent.EngineProvenance.invocation(context,
          io.justsearch.agent.api.registry.ExecutorTag.UI, java.time.Instant.now(), java.util.Optional.empty());
      var handler = new io.justsearch.app.services.registry.operations.handlers.ForgetIndexedPathHandler(f.root::forgetService);
      for (String scope : List.of("FILE", "FOLDER")) {
        for (Path target : List.of(watched, watched.getParent())) {
          if (scope.equals("FILE") && !target.equals(watched)) continue;
          var refusal = assertThrows(io.justsearch.agent.api.registry.OperationPreparationRefused.class,
              () -> handler.prepare(JSON.writeValueAsString(java.util.Map.of("path", target.toString(), "scope", scope)), provenance, context));
          assertTrue(refusal.refusal().message().contains("Remove the folder in Library"), refusal.refusal().message());
        }
      }
      for (Path target : List.of(f.config.currentSnapshot().paths().dataDir().resolve("agent-history"),
          f.config.currentSnapshot().collections().bundledHelp().path())) {
        var refusal = assertThrows(io.justsearch.agent.api.registry.OperationPreparationRefused.class,
            () -> handler.prepare(JSON.writeValueAsString(java.util.Map.of("path", target.toAbsolutePath().normalize().toString(), "scope", "FOLDER")), provenance, context));
        assertTrue(refusal.refusal().message().contains("managed by its own data controls"), refusal.refusal().message());
      }
      assertTrue(f.settings.inspect().settings().getExcludePatterns().isEmpty());
      assertTrue(f.operations.openRecords().isEmpty());
    }
  }

}
