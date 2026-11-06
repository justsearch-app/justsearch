/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.excludes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.TestEngineContexts;
import java.nio.file.*;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProtectedCleanupUncertaintyTest {
  @TempDir Path directory;
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aConfiguredDataAliasReconcilesHistoryInTheApplicationsOwnNamespace(boolean alternateAlias) throws Exception {
    Path physical = Files.createDirectory(directory.resolve("physical-parent"));
    Files.createDirectory(physical.resolve("data"));
    Path data = Files.createSymbolicLink(directory.resolve("configured-parent"), physical).resolve("data");
    Path transcript = Files.writeString(Files.createDirectory(data.resolve("agent-history")).resolve("run.md"), "private");
    Path source = alternateAlias
        ? Files.createSymbolicLink(directory.resolve("other-parent"), physical).resolve("data/agent-history/run.md") : transcript;
    var indexing = mock(IndexingService.class);
    when(indexing.getWatchedRoots(any())).thenReturn(List.of());
    when(indexing.deleteDocByIdWithOutcome(anyString(), any()))
        .thenReturn(new IndexingService.DeletionOutcome(true, "removed"));
    doAnswer(call -> {
      Consumer<IndexingService.IndexedSource> emit = call.getArgument(0);
      emit.accept(new IndexingService.IndexedSource(source.toString(), null));
      return 0L;
    }).when(indexing).forEachIndexedSourceWithCollection(any(), any());
    var reconciled = new java.util.ArrayList<Path>();
    assertTrue(new ExcludesServiceImpl(() -> indexing).removeProtectedSources(
        data, TestEngineContexts.durableInternal(), reconciled::add));
    assertEquals(List.of(transcript), reconciled, "use the same transcript ID as the application's history indexer");
    verify(indexing).deleteDocByIdWithOutcome(eq(source.toString()), any());
    assertEquals("private", Files.readString(transcript));
  }

  @Test void offlineOrdinarySourcesDoNotBlockCompletionAndAreNeverDeleted() throws Exception {
    Path data = Files.createDirectory(directory.resolve("data"));
    var indexing = mock(IndexingService.class);
    when(indexing.getWatchedRoots(any())).thenReturn(List.of());
    doAnswer(call -> {
      Consumer<IndexingService.IndexedSource> emit = call.getArgument(0);
      emit.accept(new IndexingService.IndexedSource(directory.resolve("offline/notes.txt").toString(), null));
      emit.accept(new IndexingService.IndexedSource(directory.resolve("offline/agent-history/run.md").toString(), "personal"));
      return 0L;
    }).when(indexing).forEachIndexedSourceWithCollection(any(), any());
    var cleanup = new ExcludesServiceImpl(() -> indexing);
    assertTrue(cleanup.removeProtectedSources(data, TestEngineContexts.durableInternal(), ignored -> fail("ordinary source")));
    assertEquals(io.justsearch.configuration.ProtectedContentPolicy.VERSION,
        Files.readString(data.resolve(".protected-content-version")));
    verify(indexing, never()).deleteDocByIdWithOutcome(anyString(), any());
  }

  @Test void danglingAliasAndOfflineOrdinarySourcesBlockCompletionWithoutAuthorizingDeletion() throws Exception {
    Path data = Files.createDirectory(directory.resolve("data"));
    Path offline = directory.resolve("offline-data");
    Files.writeString(Files.createDirectory(data.resolve("agent-history")).resolve("run.md"), "private");
    Path alias = Files.createSymbolicLink(directory.resolve("alias"), data);
    Files.move(data, offline);
    Files.createDirectory(data);
    var indexing = mock(IndexingService.class);
    when(indexing.getWatchedRoots(any())).thenReturn(List.of());
    doAnswer(call -> {
      Consumer<IndexingService.IndexedSource> emit = call.getArgument(0);
      emit.accept(new IndexingService.IndexedSource(alias.resolve("agent-history/run.md").toString(), null));
      emit.accept(new IndexingService.IndexedSource(directory.resolve("offline-ordinary.txt").toString(), "public"));
      return 0L;
    }).when(indexing).forEachIndexedSourceWithCollection(any(), any());
    var cleanup = new ExcludesServiceImpl(() -> indexing);
    assertFalse(cleanup.removeProtectedSources(data, TestEngineContexts.durableInternal(), ignored -> fail("unresolved history")));
    assertFalse(Files.exists(data.resolve(".protected-content-version")));
    verify(indexing, never()).deleteDocByIdWithOutcome(anyString(), any());
    assertTrue(Files.exists(offline.resolve("agent-history/run.md")));
  }
}
