/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.indexerworker.extract.EngineOwnedPaths;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EngineOwnedPathsTest {
  @TempDir Path temp;

  @Test
  void allEngineRootsShareOneClassifierAndTheSourcePathTakesPrecedence() {
    var config = new ResolvedConfigBuilder();
    config.putDefault("justsearch.data.dir", temp.getParent().resolveSibling("owned-data-" + temp.getFileName()).toString());
    config.putDefault("justsearch.index.base_path", temp.getParent().resolveSibling("owned-index-" + temp.getFileName()).toString());
    var resolved = config.build();
    var paths = new EngineOwnedPaths(resolved);
    Path source = temp.resolve("read-only-source.txt");
    for (Path owned : new Path[] {resolved.paths().dataDir().resolve("jobs.db"),
        resolved.paths().dataDir().resolve("identities.db"), resolved.paths().dataDir().resolve("paths.db"),
        resolved.paths().indexBasePath().resolve("segments_1"),
        Path.of(System.getProperty("java.io.tmpdir"), "justsearch-parse-input-missing", "input.rtf")}) {
      for (String reason : new String[] {"read-only", "full", "unrelated text"}) {
        Throwable error = new IllegalStateException("wrapper", new FileSystemException(owned.toString(), null, reason));
        assertTrue(paths.ownsFailure(error, source), "owned path decides, independent of reason: " + owned);
      }
    }
    var sourceError = new AccessDeniedException(source.toString());
    assertFalse(paths.ownsFailure(sourceError, source), "source is per-file even below the engine temp root");
    assertTrue(EngineOwnedPaths.sourceFailure(sourceError, source));
    assertFalse(paths.ownsFailure(new IOException("disk full but no path"), source), "text is not provenance");
    assertFalse(paths.ownsFailure(new AccessDeniedException("relative-document.txt"), source));
  }
}
