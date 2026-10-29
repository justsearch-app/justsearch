/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.services.RootWatcherRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
final class EngineUnconfirmedRootRemovalTest {
  @ParameterizedTest
  @ValueSource(strings = {"missing-directory", "unreadable-declarations"})
  void unconfirmedRemovalCannotSucceedWithRegisteredWatcher(String wiring, @TempDir Path directory)
      throws Exception {
    try (var fixture = EngineRootRetirementTest.fixture(directory, 1, IndexingPacing.unthrottled(), false)) {
      Path other = Files.createDirectory(directory.resolve("other-authority"));
      Files.writeString(other.resolve("watched_roots.json"), "{");
      fixture.service().setDeclaredRootsDirectory(wiring.equals("missing-directory") ? null : other);
      assertRefusedAndRetry(fixture);
    }
  }

  @Test
  void plainPrefixDeletionKeepsWatcherWithReadableDeclarations(@TempDir Path directory) throws Exception {
    try (var fixture = EngineRootRetirementTest.fixture(directory, 1, IndexingPacing.unthrottled(), false)) {
      var registry = new RootWatcherRegistry();
      fixture.service().setRootWatcherRegistry(registry);
      assertTrue(fixture.client().watchRoot(fixture.root().toString(), "documents",
          TestEngineContexts.FOREGROUND).getWatching());
      String declarations = Files.readString(fixture.data().resolve("watched_roots.json"));
      fixture.queue().enqueueEntries(
          List.of(JobQueue.EnqueueEntry.stat(fixture.root().resolve("entry-0.txt"))), "documents");
      assertEquals(1, fixture.queue().queueDepth());
      assertTrue(assertDoesNotThrow(() -> fixture.client().deleteDocsByPathPrefix(
          fixture.root(), TestEngineContexts.FOREGROUND)) >= 0);
      assertEquals(0, fixture.queue().queueDepth(), "plain prefix deletion must delete queued files");
      var watched = RootWatcherRegistry.class.getDeclaredMethod("watchedRoots");
      watched.setAccessible(true);
      assertEquals(Set.of(fixture.root()), watched.invoke(registry));
      assertTrue(fixture.roots().watchedPaths().contains(fixture.root()));
      assertEquals(declarations, Files.readString(fixture.data().resolve("watched_roots.json")));
      org.mockito.Mockito.verify(fixture.runtime().indexingCoordinator())
          .deleteByPathPrefix(fixture.root().toString());
    }
  }

  private static void assertRefusedAndRetry(EngineRootRetirementTest.Fixture fixture) throws Exception {
    var registry = new RootWatcherRegistry();
    fixture.service().setRootWatcherRegistry(registry);
    assertTrue(fixture.client().watchRoot(fixture.root().toString(), "documents",
        TestEngineContexts.FOREGROUND).getWatching());
    var watched = RootWatcherRegistry.class.getDeclaredMethod("watchedRoots");
    watched.setAccessible(true);
    long queued = fixture.queue().queueDepth();
    int removed;
    try {
      removed = fixture.client().removeWatchedPath(fixture.root(), TestEngineContexts.FOREGROUND);
    } catch (KnowledgeClientException refusal) {
      assertEquals(KnowledgeClientException.Status.UNAVAILABLE, refusal.status());
      removed = -1;
    }
    if (removed >= 0) {
      assertEquals(Set.of(), watched.invoke(registry),
          "successful removal must not leave an unconfirmed root in the watcher registry");
      assertFalse(fixture.roots().watchedPaths().contains(fixture.root()));
      assertFalse(Files.readString(fixture.data().resolve("watched_roots.json"))
          .contains("Removal incomplete. Live watching is not confirmed; retry Remove."));
      return;
    }
    assertEquals(Set.of(fixture.root()), watched.invoke(registry));
    assertEquals(queued, fixture.queue().queueDepth());
    org.mockito.Mockito.verify(fixture.runtime().indexingCoordinator(), org.mockito.Mockito.never())
        .deleteByPathPrefix(fixture.root().toString());
    assertTrue(fixture.roots().watchedPaths().contains(fixture.root()));
    assertTrue(Files.readString(fixture.data().resolve("watched_roots.json"))
        .contains("Removal incomplete. Live watching is not confirmed; retry Remove."));

    fixture.service().setDeclaredRootsDirectory(fixture.data());
    assertTrue(fixture.client().removeWatchedPath(fixture.root(), TestEngineContexts.FOREGROUND) >= 0);
    assertEquals(Set.of(), watched.invoke(registry));
    assertFalse(fixture.roots().watchedPaths().contains(fixture.root()));
    assertFalse(Files.readString(fixture.data().resolve("watched_roots.json"))
        .contains("Removal incomplete. Live watching is not confirmed; retry Remove."));
  }
}
