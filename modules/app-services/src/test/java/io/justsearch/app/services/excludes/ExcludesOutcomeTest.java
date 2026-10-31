/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.excludes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.ExcludesService.ExcludesResult;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ExcludesOutcomeTest {
  @TempDir Path root;
  private ConfigStore previous;
  // The existing per-file fake models a completed synchronous deletion, not buffered acceptance.
  // Reflection keeps the same oracles runnable against the before-state's interface.
  private final IndexingService indexing = mock(IndexingService.class, call -> {
    if (call.getMethod().getName().equals("forEachIndexedSource")) return 0L;
    if (call.getMethod().getName().equals("deleteDocByIdWithOutcome")) {
      boolean completed = ((IndexingService) call.getMock()).deleteDocById(call.getArgument(0), call.getArgument(1));
      return call.getMethod().getReturnType().getConstructor(boolean.class, String.class)
          .newInstance(completed, completed ? "" : "Worker did not confirm deletion");
    }
    return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
  });
  private final io.justsearch.core.context.EngineContext context = TestEngineContexts.durableInternal();
  private final ExcludesServiceImpl service = new ExcludesServiceImpl(() -> indexing);

  @BeforeEach void setup() {
    previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", "[\"*.log\"]").build()));
    when(indexing.getWatchedRoots(context)).thenReturn(List.of(new IndexingService.WatchedRoot("docs", root)));
  }
  @AfterEach void restore() { TestResolvedConfigHelper.restoreGlobal(previous); }
  private static JsonNode wire(ExcludesResult result) { return new ObjectMapper().valueToTree(result); }

  @Test void WX1CountsOnlyWorkerConfirmedRequestsAndRetryReconciles() throws Exception {
    var first = Files.writeString(root.resolve("a.log"), "a");
    var second = Files.writeString(root.resolve("b.log"), "b");
    when(indexing.deleteDocById(anyString(), eq(context))).thenAnswer(call -> call.getArgument(0).equals(first.toString()));
    var applied = wire(service.applyExcludes(false, context));
    assertEquals(1, applied.path("confirmedRemovals").asInt(-1));
    assertEquals(1, applied.path("uncheckedScopes").size());
    assertEquals(second.toString(), applied.path("uncheckedScopes").get(0).path("path").asText());
    assertEquals("not_removed", applied.path("uncheckedScopes").get(0).path("status").asText());
    when(indexing.deleteDocById(anyString(), eq(context))).thenReturn(true);
    var retry = wire(service.applyExcludes(false, context));
    assertEquals(2, retry.path("confirmedRemovals").asInt(-1), "includes confirmed requests for never-indexed or already absent matches");
    assertTrue(retry.path("uncheckedScopes").isArray());
    assertEquals(0, retry.path("uncheckedScopes").size());
  }

  @Test void S323OperationWireRetainsPartialProgressAndScope() throws Exception {
    var file = Files.writeString(root.resolve("refused.log"), "text");
    when(indexing.deleteDocById(anyString(), eq(context))).thenReturn(false);
    var result = new io.justsearch.app.services.registry.operations.handlers.ApplyExcludesHandler(() -> service)
        .execute("{}", context);
    assertTrue(result.success(), "a structured partial result retains progress instead of throwing it away");
    assertEquals(0, result.structuredData().get("confirmedRemovals"));
    var scopes = new ObjectMapper().valueToTree(result.structuredData().get("uncheckedScopes"));
    assertEquals(1, scopes.size());
    assertEquals(file.toString(), scopes.get(0).path("path").asText());
    assertEquals("not_removed", scopes.get(0).path("status").asText());
  }

  @Test void WX1UnconfirmedPrefixNamesItsWholeScopeWithoutCountingDescendants() throws Exception {
    var directory = Files.createDirectory(root.resolve("private"));
    Files.writeString(directory.resolve("child.log"), "private");
    ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
        .putDefault("justsearch.ui.exclude_patterns", "[\"private/\"]").build()));
    // The default mock cannot confirm a prefix deletion, even if the old job count was zero.
    var result = wire(service.applyExcludes(false, context));
    assertEquals(0, result.path("confirmedRemovals").asInt(-1));
    assertEquals(1, result.path("uncheckedScopes").size());
    assertEquals(directory.toString(), result.path("uncheckedScopes").get(0).path("path").asText());
    assertEquals("not_removed", result.path("uncheckedScopes").get(0).path("status").asText());
    verify(indexing, never()).deleteDocById(anyString(), any());
  }

  @Test void S323MissingWatchedRootIsNamedInPreviewAndApply() throws Exception {
    var missing = root.resolve("offline");
    when(indexing.getWatchedRoots(context)).thenReturn(List.of(new IndexingService.WatchedRoot("docs", missing)));
    for (boolean dryRun : List.of(true, false)) {
      var result = wire(service.applyExcludes(dryRun, context));
      assertEquals(1, result.path("uncheckedScopes").size());
      var scope = result.path("uncheckedScopes").get(0);
      assertEquals(missing.toString(), scope.path("path").asText());
      assertEquals("not_checked", scope.path("status").asText());
      assertTrue(scope.path("reason").asText().contains("offline or missing"));
    }
    verify(indexing, never()).deleteDocById(anyString(), any());
  }

  @Test void S323RefusalPartwayRetainsConfirmedProgressAndNamesFailedScope() throws Exception {
    Files.writeString(root.resolve("a.log"), "a");
    Files.writeString(root.resolve("b.log"), "b");
    when(indexing.deleteDocById(anyString(), eq(context))).thenReturn(true).thenThrow(
        new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 3));
    var result = wire(assertDoesNotThrow(() -> service.applyExcludes(false, context)));
    assertEquals(1, result.path("confirmedRemovals").asInt(-1));
    assertEquals(1, result.path("uncheckedScopes").size());
    assertEquals("not_removed", result.path("uncheckedScopes").get(0).path("status").asText());
  }

  @Test void S323UnreadableEntriesAreNamedInBothModes() throws Exception {
    if (!Files.getFileStore(root).supportsFileAttributeView("posix")) return;
    var file = Files.writeString(root.resolve("secret.log"), "private");
    var directory = Files.createDirectory(root.resolve("private"));
    var filePermissions = Files.getPosixFilePermissions(file);
    var dirPermissions = Files.getPosixFilePermissions(directory);
    try {
      Files.setPosixFilePermissions(file, Set.of());
      Files.setPosixFilePermissions(directory, Set.of());
      assertFalse(Files.isReadable(file), "fixture must be unreadable");
      for (boolean dryRun : List.of(true, false)) {
        var result = wire(service.applyExcludes(dryRun, context));
        assertEquals(2, result.path("uncheckedScopes").size());
        var paths = new java.util.HashSet<String>();
        for (var scope : result.path("uncheckedScopes")) {
          paths.add(scope.path("path").asText());
          assertEquals("not_checked", scope.path("status").asText());
        }
        assertEquals(Set.of(file.toString(), directory.toString()), paths);
      }
    } finally {
      Files.setPosixFilePermissions(file, filePermissions);
      Files.setPosixFilePermissions(directory, dirPermissions);
    }
  }

  @Test void S323CapNamesCurrentAndRemainingRootsWithoutDeletingBeyondIt() throws Exception {
    Files.writeString(root.resolve("a.log"), "a"); Files.writeString(root.resolve("b.log"), "b");
    Path later = Files.createDirectory(root.resolve("later"));
    when(indexing.getWatchedRoots(context)).thenReturn(List.of(
        new IndexingService.WatchedRoot("docs", root), new IndexingService.WatchedRoot("later", later)));
    // The production default is 500,000 entries; inject a small budget to exercise the same walk.
    var constructor = java.util.Arrays.stream(ExcludesServiceImpl.class.getDeclaredConstructors())
        .filter(c -> c.getParameterCount() == 2).findFirst();
    assertTrue(constructor.isPresent(), "bounded walk must report cap coverage");
    var bounded = (ExcludesServiceImpl) constructor.orElseThrow().newInstance(
        (java.util.function.Supplier<IndexingService>) () -> indexing, 1);
    var result = wire(bounded.applyExcludes(false, context));
    assertTrue(result.path("capped").asBoolean());
    assertEquals(2, result.path("uncheckedScopes").size());
    assertEquals(0, result.path("confirmedRemovals").asInt(-1));
    verify(indexing, never()).deleteDocById(anyString(), any());
  }
}
