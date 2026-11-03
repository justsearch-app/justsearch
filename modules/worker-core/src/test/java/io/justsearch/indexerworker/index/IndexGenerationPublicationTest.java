/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.SafeIndexPathOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexGenerationPublicationTest {
  @TempDir Path temp;

  @Test void freshCreationNeverExposesIncompleteOwnership() throws Exception {
    observeCreation(() -> new IndexGenerationManager(temp).initializeOrLoad());
  }

  @Test void nativeMigrationNeverExposesIncompleteOwnership() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    observeCreation(() -> manager.startFreshMigration("manual"));
  }

  @Test void recordedCreationNeverExposesIncompleteOwnership() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    observeCreation(() -> manager.startRecordedMigration("01994180-0000-7000-8000-000000000119",
        "recorded-publication", "a".repeat(64)));
  }

  @Test void recoveredCreationNeverExposesIncompleteOwnership() throws Exception {
    var manager = new IndexGenerationManager(temp);
    Path active = manager.initializeOrLoad().activeGenerationPath();
    SafeIndexPathOps.backupDirectory(active, temp.resolve("indices"));
    observeCreation(manager::reconcileActiveRecoveryOwnership);
  }

  @Test void publicationCollisionKeepsEmptyDirectory() throws Exception { collision("empty"); }
  @Test void publicationCollisionKeepsNonEmptyDirectory() throws Exception { collision("payload"); }
  @Test void publicationCollisionKeepsRegularFile() throws Exception { collision("file"); }
  @Test void publicationCollisionKeepsLinkTarget() throws Exception { collision("link"); }

  private void collision(String kind) throws Exception {
    Path external = Files.createDirectory(temp.resolve("external"));
    Files.writeString(external.resolve("foreign"), "external bytes");
    Path base = temp.resolve("base");
    var injected = new java.util.concurrent.atomic.AtomicBoolean();
    var target = new java.util.concurrent.atomic.AtomicReference<Path>();
    try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      org.mockito.stubbing.Answer<Object> create = invocation -> {
        Path path = invocation.getArgument(0);
        Path parent = path.getParent();
        if (parent != null && (parent.equals(base.resolve("indices"))
            || parent.equals(base.resolve("staging"))) && injected.compareAndSet(false, true)) {
          String name = path.getFileName().toString();
          if (parent.equals(base.resolve("staging"))) name = name.substring(0, name.lastIndexOf('.'));
          Path occupied = base.resolve("indices").resolve(name);
          target.set(occupied);
          switch (kind) {
            case "empty" -> Files.createDirectory(occupied);
            case "payload" -> Files.writeString(Files.createDirectory(occupied).resolve("foreign"), "foreign bytes");
            case "file" -> Files.writeString(occupied, "foreign file");
            case "link" -> Files.createSymbolicLink(occupied, external);
            default -> fail(kind);
          }
        }
        return invocation.callRealMethod();
      };
      files.when(() -> Files.createDirectories(any(Path.class), any(FileAttribute[].class))).thenAnswer(create);
      files.when(() -> Files.createDirectory(any(Path.class), any(FileAttribute[].class))).thenAnswer(create);
      assertThrows(java.io.IOException.class, () -> new IndexGenerationManager(base).initializeOrLoad());
      assertTrue(injected.get());
      Path occupied = target.get();
      assertFalse(Files.exists(base.resolve("state.json")));
      assertFalse(Files.exists(occupied.resolve(".justsearch-generation.sentinel")));
      if (kind.equals("payload")) assertEquals("foreign bytes", Files.readString(occupied.resolve("foreign")));
      if (kind.equals("file")) assertEquals("foreign file", Files.readString(occupied));
      if (kind.equals("link")) assertTrue(Files.isSymbolicLink(occupied));
      assertEquals("external bytes", Files.readString(external.resolve("foreign")));
    }
  }

  private void observeCreation(CheckedAction action) throws Exception {
    try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files.when(() -> Files.createDirectories(any(Path.class), any(FileAttribute[].class)))
          .thenAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            assertPublishedOwnership();
            return result;
          });
      files.when(() -> Files.createDirectory(any(Path.class), any(FileAttribute[].class)))
          .thenAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            assertPublishedOwnership();
            return result;
          });
      assertDoesNotThrow(action::run);
      assertPublishedOwnership();
    }
  }

  private void assertPublishedOwnership() throws Exception {
    Path indices = temp.resolve("indices");
    if (!Files.exists(indices)) return;
    try (var entries = Files.list(indices)) {
      for (Path path : entries.filter(Files::isDirectory).toList()) {
        assertTrue(Files.isRegularFile(path.resolve(".justsearch-generation.sentinel")),
            "published directory is missing its sentinel: " + path);
        assertTrue(Files.isRegularFile(path.resolve(".justsearch-index-generation.json")),
            "published directory is missing its manifest: " + path);
      }
    }
  }

  private interface CheckedAction { void run() throws Exception; }
}
