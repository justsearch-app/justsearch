/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

final class PathPrefixScopeTest {
  @Test
  void literalsAndDirectoryBoundariesSurviveReceiptRoundTrip() {
    var scope = new PathPrefixScope("/a/notes_%/", List.of("/a/notes_%/child/"));
    var replayed = PathPrefixScope.decode(scope.encode());
    assertEquals(scope, replayed);
    assertTrue(replayed.contains("/a/notes_%/own.txt"));
    assertFalse(replayed.contains("/a/notes_%/child/file.txt"));
    assertFalse(replayed.contains("/a/notes_%2/sibling.txt"));
    assertTrue(replayed.contains("/a/notes_%/child2/file.txt"));
  }

  @Test
  void malformedReceiptsAndEscapingExclusionsAreRefused() {
    assertThrows(IllegalArgumentException.class,
        () -> new PathPrefixScope("/a", List.of("/ab")));
    assertThrows(IllegalArgumentException.class,
        () -> new PathPrefixScope("/a", List.of("/a")));
    assertThrows(IllegalArgumentException.class,
        () -> PathPrefixScope.decode("{\"version\":2,\"prefix\":\"/a\",\"excludedSubtrees\":[]}"));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void windowsDriveAndUncSpellingsHaveTheSameFrozenBoundaries() {
    for (String root : List.of("C:\\Notes_%", "\\\\server\\share\\Notes_%")) {
      var scope = new PathPrefixScope(root.toUpperCase(java.util.Locale.ROOT) + "//",
          List.of(root.replace('\\', '/') + "/Child//"));
      assertTrue(scope.contains(root + "\\own.txt"));
      assertFalse(scope.contains(root.toLowerCase(java.util.Locale.ROOT) + "\\child\\file.txt"));
      assertFalse(scope.contains(root + "2\\sibling.txt"));
      assertEquals(scope, PathPrefixScope.decode(scope.encode()));
    }
  }
}
