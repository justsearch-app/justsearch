/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.services.RootWatcherRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
final class EngineUnconfirmedRootRemovalTest {
  @ParameterizedTest
  @ValueSource(strings = {"missing-directory", "absent-intent"})
  void unconfirmedRemovalCannotSucceedWithRegisteredWatcher(String wiring, @TempDir Path directory)
      throws Exception {
    try (var fixture = EngineRootRetirementTest.fixture(directory, 1, IndexingPacing.unthrottled(), false)) {
      Path other = Files.createDirectory(directory.resolve("other-authority"));
      fixture.service().setDeclaredRootsDirectory(wiring.equals("missing-directory") ? null : other);
      assertRefusedAndRetry(fixture);
    }
  }

  @Test
  void unreadableDeclarationsKeepRemovalRetryable(@TempDir Path directory) throws Exception {
    try (var fixture = EngineRootRetirementTest.fixture(directory, 1, IndexingPacing.unthrottled(), false)) {
      Path other = Files.createDirectory(directory.resolve("unreadable-authority"));
      Files.writeString(other.resolve("watched_roots.json"), "{");
      fixture.service().setDeclaredRootsDirectory(other);
      assertRefusedAndRetry(fixture);
    }
  }

  private static void assertRefusedAndRetry(EngineRootRetirementTest.Fixture fixture) throws Exception {
    var registry = new RootWatcherRegistry();
    fixture.service().setRootWatcherRegistry(registry);
    assertTrue(fixture.client().watchRoot(fixture.root().toString(), "documents",
        TestEngineContexts.FOREGROUND).getWatching());
    var watched = RootWatcherRegistry.class.getDeclaredMethod("watchedRoots");
    watched.setAccessible(true);
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
