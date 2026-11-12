/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.excludes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedExcludesTest {
  @TempDir Path directory;

  @Test void D16IndexedOutOfRootSourcesAreMatchedWhenMissingOnDiskAndWithNoWatchedRoots() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", "[\"*.log\"]").build()));
    try {
      var context = TestEngineContexts.durableInternal();
      String selected = directory.resolve("missing.log").toString(), neighbour = directory.resolve("keep.txt").toString();
      AtomicBoolean reject = new AtomicBoolean();
      IndexingService indexing = mock(IndexingService.class, call -> {
        if ((call.getMethod().getName().equals("forEachIndexedSource")
            || call.getMethod().getName().equals("forEachIndexedSourceWithOrigin"))) {
          java.util.function.Consumer<String> consumer = indexedConsumer(call, null);
          List.of(selected, selected, neighbour).forEach(consumer);
          return 0L;
        }
        if (call.getMethod().getName().equals("deleteDocByIdWithOutcome")) {
          return call.getMethod().getReturnType().getConstructor(boolean.class, String.class)
              .newInstance(!reject.get(), reject.get() ? "injected deletion failure" : "");
        }
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
      });
      var service = new ExcludesServiceImpl(() -> indexing);
      var preview = service.applyExcludes(true, context);
      assertEquals(1, preview.matchedFiles()); assertEquals(0, preview.confirmedRemovals());
      assertTrue(preview.uncheckedScopes().isEmpty());
      var applied = service.applyExcludes(false, context);
      assertEquals(1, applied.confirmedRemovals()); assertEquals(1, applied.deletedById());
      reject.set(true);
      var failed = service.applyExcludes(false, context);
      assertEquals(0, failed.confirmedRemovals()); assertEquals(1, failed.uncheckedScopes().size());
      assertEquals("not_removed", failed.uncheckedScopes().getFirst().status());
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  @Test void D16EnumerationFailureAndBudgetExhaustionRemainExplicitlyUnchecked() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", "[\"*.log\"]").build()));
    try {
      var context = TestEngineContexts.durableInternal();
      AtomicBoolean fail = new AtomicBoolean(true);
      IndexingService indexing = mock(IndexingService.class, call -> {
        if ((call.getMethod().getName().equals("forEachIndexedSource")
            || call.getMethod().getName().equals("forEachIndexedSourceWithOrigin"))) {
          if (fail.get()) throw new IllegalStateException("injected enumeration failure");
          java.util.function.Consumer<String> consumer = indexedConsumer(call, null);
          consumer.accept(directory.resolve("one.log").toString());
          consumer.accept(directory.resolve("two.log").toString());
          return 0L;
        }
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
      });
      var service = new ExcludesServiceImpl(() -> indexing, 1);
      assertFalse(service.applyExcludes(true, context).uncheckedScopes().isEmpty());
      fail.set(false);
      var capped = service.applyExcludes(true, context);
      assertTrue(capped.capped()); assertEquals(1, capped.matchedFiles());
      assertFalse(capped.uncheckedScopes().isEmpty());
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"name", "glob", "literal"})
  void C14WatchedAndAdHocMatchingShareWorkerPolicyAndLeaveNeighbours(String style) throws Exception {
    Path watched = java.nio.file.Files.createDirectory(directory.resolve("watched"));
    Path adHoc = java.nio.file.Files.createDirectory(directory.resolve("adhoc"));
    Path watchedFile = java.nio.file.Files.writeString(watched.resolve("private.log"), "text");
    Path adHocFile = adHoc.resolve("private.log"), neighbour = adHoc.resolve("keep.txt");
    var entries = switch (style) {
      case "name" -> List.of("private.log");
      case "glob" -> List.of("*.log");
      case "literal" -> List.of(
          io.justsearch.configuration.excludes.LiteralExclusion.create(watchedFile,
              io.justsearch.configuration.excludes.LiteralExclusion.Scope.FILE),
          io.justsearch.configuration.excludes.LiteralExclusion.create(adHocFile,
              io.justsearch.configuration.excludes.LiteralExclusion.Scope.FILE));
      default -> throw new AssertionError(style);
    };
    String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(entries);
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", json).build()));
    try {
      var context = TestEngineContexts.durableInternal();
      IndexingService indexing = mock(IndexingService.class, call -> {
        if (call.getMethod().getName().equals("getWatchedRoots"))
          return List.of(new IndexingService.WatchedRoot("docs", watched));
        if ((call.getMethod().getName().equals("forEachIndexedSource")
            || call.getMethod().getName().equals("forEachIndexedSourceWithOrigin"))) {
          java.util.function.Consumer<String> consumer = indexedConsumer(call, null);
          List.of(watchedFile, adHocFile, neighbour).forEach(path -> consumer.accept(path.toString()));
          return 0L;
        }
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
      });
      var preview = new ExcludesServiceImpl(() -> indexing).applyExcludes(true, context);
      assertEquals(2, preview.matchedFiles()); assertTrue(preview.uncheckedScopes().isEmpty());
      var worker = io.justsearch.configuration.excludes.ExclusionPatterns.fromRawJsonArray(json);
      assertTrue(worker.isExcludedPath(watched, watchedFile));
      assertTrue(worker.isExcludedPath(adHocFile, adHocFile));
      assertFalse(worker.isExcludedPath(neighbour, neighbour));
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }


  @Test void C13UnreadableOwnershipRemainsExplicitlyUncheckedAfterMatchingKnownSources() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", "[\"*.log\"]").build()));
    try {
      var context = TestEngineContexts.durableInternal();
      IndexingService indexing = mock(IndexingService.class, call -> {
        if ((call.getMethod().getName().equals("forEachIndexedSource")
            || call.getMethod().getName().equals("forEachIndexedSourceWithOrigin"))) {
          java.util.function.Consumer<String> consumer = indexedConsumer(call, null);
          consumer.accept(directory.resolve("known.log").toString()); return 1L;
        }
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
      });
      var preview = new ExcludesServiceImpl(() -> indexing).applyExcludes(true, context);
      assertEquals(1, preview.matchedFiles()); assertEquals(0, preview.confirmedRemovals());
      assertEquals(1, preview.uncheckedScopes().size());
      assertEquals("not_checked", preview.uncheckedScopes().getFirst().status());
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }


  private static java.util.function.Consumer<String> indexedConsumer(org.mockito.invocation.InvocationOnMock call,
      String owner) {
    java.util.function.Consumer<Object> consumer = call.getArgument(0);
    return path -> {
      try {
        Object member = call.getMethod().getName().equals("forEachIndexedSource") ? path
            : Class.forName(IndexingService.class.getName() + "$IndexedSourceOwner")
                .getConstructor(String.class, String.class).newInstance(path, owner);
        consumer.accept(member);
      } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    };
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void U5ImportRootRelativeExclusionsRemoveOnlyMatchingMembers(boolean changedOwner) throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", "[\"inner/**\"]").build()));
    try {
      Path root = directory.resolve("import");
      String selected = root.resolve("inner/file.txt").toString(), neighbour = root.resolve("keep.txt").toString();
      var approval = new io.justsearch.app.api.indexing.SourceOrigin.Approval(
          new io.justsearch.app.api.operations.OperationAuthorizationBasis.StructuralAuto().encode(),
          "SYSTEM_INTERNAL", "TRUSTED", "CLI", "core.ingest-files", "import-operation",
          io.justsearch.app.api.indexing.SourceOrigin.ApprovalClass.DURABLE,
          io.justsearch.app.api.indexing.SourceOrigin.EvidenceSource.GATE);
      var origin = io.justsearch.app.api.indexing.SourceOrigin.accepted(
          io.justsearch.app.api.indexing.SourceOrigin.Kind.IMPORT,
          io.justsearch.app.api.indexing.SourceOrigin.watchedRoot(root, "docs"), approval, 1L);
      var removed = new java.util.ArrayList<String>();
      var enumerating = new AtomicBoolean();
      IndexingService indexing = mock(IndexingService.class, call -> {
        String name = call.getMethod().getName();
        if (name.equals("forEachIndexedSource") || name.equals("forEachIndexedSourceWithOrigin")) {
          enumerating.set(true);
          try { List.of(selected, neighbour).forEach(indexedConsumer(call, origin.id())); return 0L; }
          finally { enumerating.set(false); }
        }
        if (name.equals("findSourceOrigin")) {
          assertFalse(enumerating.get(), "Origin lookup must run after the worker enumeration call exits");
          return java.util.Optional.of(origin);
        }
        if (name.equals("deleteExcludedImportMember")) {
          assertEquals(origin.id(), call.getArgument(1));
          if (!changedOwner) removed.add(call.getArgument(0));
          return new IndexingService.DeletionOutcome(!changedOwner, changedOwner ? "Changed owner" : "");
        }
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
      });
      var result = new ExcludesServiceImpl(() -> indexing).applyExcludes(false, TestEngineContexts.durableInternal());
      assertEquals(1, result.matchedFiles()); assertEquals(changedOwner ? 0 : 1, result.confirmedRemovals());
      assertEquals(changedOwner ? List.of() : List.of(selected), removed);
      if (changedOwner) {
        assertEquals(1, result.uncheckedScopes().size());
        assertEquals("changed_owner", result.uncheckedScopes().getFirst().status());
      } else assertTrue(result.uncheckedScopes().isEmpty());
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

}
