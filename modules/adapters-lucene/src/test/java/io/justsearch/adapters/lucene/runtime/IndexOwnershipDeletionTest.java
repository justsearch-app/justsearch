/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexOwnershipDeletionTest {
  @TempDir Path temp;

  @Test void failedRenameNeverWritesThroughSymlinkDeleteMarker() throws Exception {
    Path folder = owned();
    Path external = temp.resolve("external");
    Files.writeString(external, "external bytes");
    Files.createSymbolicLink(folder.resolve("DELETEME"), external);
    try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files.when(() -> Files.move(eq(folder), any(Path.class), eq(StandardCopyOption.ATOMIC_MOVE)))
          .thenThrow(new IOException("injected directory rename failure"));
      var result = SafeIndexPathOps.markForDeletion(folder, temp);
      assertFalse(result.wroteMarker());
      assertEquals("external bytes", Files.readString(external));
      assertTrue(Files.isSymbolicLink(folder.resolve("DELETEME")));
    }
  }

  @Test void failedRenameReplacesHardLinkDeleteMarkerWithoutChangingExternalBytes() throws Exception {
    Path folder = owned();
    Path external = temp.resolve("external");
    Files.writeString(external, "external bytes");
    Files.createLink(folder.resolve("DELETEME"), external);
    try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files.when(() -> Files.move(eq(folder), any(Path.class), eq(StandardCopyOption.ATOMIC_MOVE)))
          .thenThrow(new IOException("injected directory rename failure"));
      var result = SafeIndexPathOps.markForDeletion(folder, temp);
      assertTrue(result.wroteMarker());
      assertEquals("external bytes", Files.readString(external));
      assertFalse(Files.isSameFile(external, folder.resolve("DELETEME")));
    }
  }

  @Test void nonRegularDeleteMarkerSkipsFallback() throws Exception {
    Path folder = owned();
    Path marker = Files.createDirectory(folder.resolve("DELETEME"));
    Files.writeString(marker.resolve("foreign"), "foreign bytes");
    try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files.when(() -> Files.move(eq(folder), any(Path.class), eq(StandardCopyOption.ATOMIC_MOVE)))
          .thenThrow(new IOException("injected directory rename failure"));
      assertFalse(SafeIndexPathOps.markForDeletion(folder, temp).wroteMarker());
      assertEquals("foreign bytes", Files.readString(marker.resolve("foreign")));
    }
  }

  private Path owned() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("g-owned"));
    Files.writeString(folder.resolve(".justsearch-generation.sentinel"),
        "justsearch_generation_sentinel_v1\ngeneration_id=g-owned\ncreated_at_ms=1\n");
    return folder;
  }
}
