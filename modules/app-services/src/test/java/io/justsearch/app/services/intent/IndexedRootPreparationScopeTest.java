/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TrustTier;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.TestEngineContexts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("IndexedRootGrantScope prepared root coverage")
class IndexedRootPreparationScopeTest {

  private static final OperationRef GOVERNED = new OperationRef("core.ingest-files");
  private static final OperationRef UNGOVERNED = new OperationRef("core.search-index");
  private static final String PUBLIC_ARGUMENTS =
      "{\"paths\":[\"relative/requested-root\"],\"generation\":\"public\"}";

  @Test
  void usesTheFrozenAbsolutePlanRatherThanRawRelativeArguments(@TempDir Path base) throws Exception {
    Path watched = Files.createDirectory(base.resolve("watched"));
    Path frozen = Files.createDirectory(watched.resolve("selected"));
    IndexedRootGrantScope scope = scopeBoundTo(watched);

    assertTrue(scope.coversPreparation(
        op(GOVERNED), preparation(PUBLIC_ARGUMENTS, plan(frozen)), TestEngineContexts.agent()));
  }

  @Test
  void watchedRootOrderAndUnrelatedAdditionsDoNotChangeCoverage(@TempDir Path base) throws Exception {
    Path watched = Files.createDirectory(base.resolve("watched"));
    Path unrelated = Files.createDirectory(base.resolve("unrelated"));
    Path frozen = Files.createDirectory(watched.resolve("selected"));
    AtomicInteger calls = new AtomicInteger();
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));
    AtomicReference<List<Path>> current = new AtomicReference<>(List.of(watched));
    scope.bindIndexedRoots(context -> {
      calls.incrementAndGet();
      return current.get();
    });
    OperationPreparation prepared = preparation(PUBLIC_ARGUMENTS, plan(frozen));

    assertTrue(scope.coversPreparation(op(GOVERNED), prepared, TestEngineContexts.agent()));
    current.set(List.of(unrelated, watched));
    assertTrue(scope.coversPreparation(op(GOVERNED), prepared, TestEngineContexts.agent()));
    assertEquals(2, calls.get(), "each permission decision snapshots watched roots once");
  }

  @Test
  void rawInRootArgumentCannotExcuseAnOutOfRootFrozenEffect(@TempDir Path base) throws Exception {
    Path watched = Files.createDirectory(base.resolve("watched"));
    Path outside = Files.createDirectory(base.resolve("outside"));
    IndexedRootGrantScope scope = scopeBoundTo(watched);

    String rawInRootArguments = tools.jackson.databind.json.JsonMapper.builder().build()
        .writeValueAsString(Map.of("paths", List.of(watched.toString())));
    assertFalse(scope.coversPreparation(op(GOVERNED),
        preparation(rawInRootArguments, plan(outside)), TestEngineContexts.agent()));
  }

  @Test
  void malformedSchemaContentAndMissingPreparationFailClosed(@TempDir Path base) throws Exception {
    Path watched = Files.createDirectory(base.resolve("watched"));
    Path frozen = Files.createDirectory(watched.resolve("selected"));
    IndexedRootGrantScope scope = scopeBoundTo(watched);
    String valid = plan(frozen);

    assertFalse(scope.coversPreparation(op(GOVERNED), null, TestEngineContexts.agent()));
    assertFalse(scope.coversPreparation(op(UNGOVERNED), preparation(PUBLIC_ARGUMENTS, valid),
        TestEngineContexts.agent()));
    assertFalse(scope.coversPreparation(op(GOVERNED),
        new OperationPreparation(PUBLIC_ARGUMENTS, RecordedRootPlan.SCHEMA, "{not-json"),
        TestEngineContexts.agent()));
    assertFalse(scope.coversPreparation(op(GOVERNED),
        new OperationPreparation(PUBLIC_ARGUMENTS, "other-root-plan.v1", valid),
        TestEngineContexts.agent()));
    assertFalse(scope.coversPreparation(op(GOVERNED),
        new OperationPreparation(PUBLIC_ARGUMENTS, RecordedRootPlan.SCHEMA, valid,
            OperationPreparation.Content.CONTENT), TestEngineContexts.agent()));
    assertFalse(scope.coversPreparation(op(GOVERNED),
        OperationPreparation.passthrough(PUBLIC_ARGUMENTS), TestEngineContexts.agent()));
    assertFalse(scope.coversPreparation(op(GOVERNED),
        preparation(PUBLIC_ARGUMENTS, new RecordedRootPlan("generation", List.of()).toReplayPayload()),
        TestEngineContexts.agent()));
  }

  @Test
  void missingOrRemovedWatchedAndFrozenPathsFailClosed(@TempDir Path base) throws Exception {
    Path watched = Files.createDirectory(base.resolve("watched"));
    Path frozen = Files.createDirectory(watched.resolve("selected"));
    AtomicReference<List<Path>> current = new AtomicReference<>(List.of(watched));
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));
    scope.bindIndexedRoots(context -> current.get());

    assertTrue(scope.coversPreparation(
        op(GOVERNED), preparation(PUBLIC_ARGUMENTS, plan(frozen)), TestEngineContexts.agent()));
    current.set(List.of());
    assertFalse(scope.coversPreparation(
        op(GOVERNED), preparation(PUBLIC_ARGUMENTS, plan(frozen)), TestEngineContexts.agent()));
    assertFalse(scope.coversPreparation(op(GOVERNED),
        preparation(PUBLIC_ARGUMENTS, plan(base.resolve("does-not-exist"))),
        TestEngineContexts.agent()));
  }

  @Test
  void missingFrozenPathInsideAnExistingWatchedRootFailsClosed(@TempDir Path watched) {
    IndexedRootGrantScope scope = scopeBoundTo(watched);
    Path missing = watched.resolve("missing-frozen-path");
    assertFalse(Files.exists(missing));
    assertFalse(scope.coversPreparation(op(GOVERNED),
        preparation(PUBLIC_ARGUMENTS, plan(missing)), TestEngineContexts.agent()));
  }

  @Test
  void aCoveredFirstRootCannotExcuseALaterUncoveredRoot(@TempDir Path base) throws Exception {
    Path watched = Files.createDirectory(base.resolve("watched"));
    Path inside = Files.createDirectory(watched.resolve("inside"));
    Path outside = Files.createDirectory(base.resolve("outside"));
    assertFalse(scopeBoundTo(watched).coversPreparation(op(GOVERNED),
        preparation(PUBLIC_ARGUMENTS, plan(inside, outside)), TestEngineContexts.agent()));
  }

  @Test
  void siblingPrefixIsNotContained(@TempDir Path base) throws Exception {
    Path watched = Files.createDirectory(base.resolve("docs"));
    Path sibling = Files.createDirectory(base.resolve("docs-private"));
    IndexedRootGrantScope scope = scopeBoundTo(watched);

    assertFalse(scope.coversPreparation(
        op(GOVERNED), preparation(PUBLIC_ARGUMENTS, plan(sibling)), TestEngineContexts.agent()));
  }

  @Test
  void symlinkOrJunctionEscapeIsNotContained(@TempDir Path base) throws Exception {
    Path watched = Files.createDirectory(base.resolve("watched"));
    Path outside = Files.createDirectory(base.resolve("outside"));
    Path secret = Files.createFile(outside.resolve("secret.txt"));
    Path link = watched.resolve("link");
    linkDirectory(link, outside);
    Path escaping = link.resolve(secret.getFileName());
    assertTrue(Files.exists(escaping));
    assertTrue(escaping.normalize().startsWith(watched));

    assertFalse(scopeBoundTo(watched).coversPreparation(
        op(GOVERNED), preparation(PUBLIC_ARGUMENTS, plan(escaping)), TestEngineContexts.agent()));
  }

  @Test
  void duplicateAndNestedPartitionRootsRemainCovered(@TempDir Path base) throws Exception {
    Path watched = Files.createDirectory(base.resolve("watched"));
    Path nested = Files.createDirectory(watched.resolve("nested"));
    RecordedRootPlan plan = new RecordedRootPlan("generation", List.of(
        root(watched), root(watched), root(nested)));

    assertTrue(scopeBoundTo(watched).coversPreparation(
        op(GOVERNED), preparation(PUBLIC_ARGUMENTS, plan.toReplayPayload()),
        TestEngineContexts.agent()));
  }

  private static IndexedRootGrantScope scopeBoundTo(Path... roots) {
    IndexedRootGrantScope scope = new IndexedRootGrantScope(Set.of(GOVERNED));
    scope.bindIndexedRoots(context -> List.of(roots));
    return scope;
  }

  private static OperationPreparation preparation(String arguments, String payload) {
    return new OperationPreparation(arguments, RecordedRootPlan.SCHEMA, payload);
  }

  private static String plan(Path... paths) {
    return new RecordedRootPlan("generation", java.util.Arrays.stream(paths)
        .map(IndexedRootPreparationScopeTest::root).toList()).toReplayPayload();
  }

  private static RecordedRootPlan.Root root(Path path) {
    return new RecordedRootPlan.Root(path, null, false, false, List.of(), List.of());
  }

  private static Operation op(OperationRef id) {
    return new Operation(
        id,
        Presentation.of(new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(RiskTier.MEDIUM, ConfirmStrategy.Inline.INSTANCE, AuditPolicy.NONE,
            RetryPolicy.noRetry(), Set.of(), false),
        OperationAvailability.empty(), OperationLineage.empty(), Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"), Set.of(ExecutorTag.AGENT));
  }

  private static void linkDirectory(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
      return;
    } catch (IOException | UnsupportedOperationException e) {
      if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
        Assumptions.abort("Platform cannot create symbolic links: " + e.getMessage());
      }
    }
    try {
      Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
          .redirectErrorStream(true).start();
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroy();
        Assumptions.abort("Platform cannot create a directory junction either");
      }
    } catch (IOException e) {
      Assumptions.abort("Platform cannot create a directory junction either: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Assumptions.abort("Interrupted while creating a directory junction");
    }
  }
}
