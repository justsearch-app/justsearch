/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Storage provenance shared by all ingestion failure classifiers; never inspect error text. */
public final class EngineOwnedPaths {
  private final List<Path> roots = new ArrayList<>();

  public EngineOwnedPaths(ResolvedConfig config) {
    add(Path.of(System.getProperty("java.io.tmpdir")));
    if (config != null && config.paths() != null) {
      add(config.paths().dataDir()); // jobs.db, identity and path stores live below this root.
      add(config.paths().indexBasePath()); // May be configured outside the data folder.
    }
  }

  private void add(Path root) {
    if (root == null) return;
    roots.add(root.toAbsolutePath().normalize());
    try { roots.add(root.toRealPath()); }
    catch (IOException absent) { /* Retain lexical provenance for missing storage. */ }
  }

  public static boolean sourceFailure(Throwable failure, Path source) {
    if (source == null) return false;
    Path normalized = source.toAbsolutePath().normalize();
    for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
      if (failure instanceof FileSystemException io && io.getFile() != null) {
        try { if (Path.of(io.getFile()).normalize().equals(normalized)) return true; }
        catch (InvalidPathException invalid) { /* Not a usable source path. */ }
      }
    }
    return false;
  }

  public boolean ownsFailure(Throwable failure, Path source) {
    boolean owned = false;
    for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
      if (!(failure instanceof FileSystemException io)) continue;
      for (String name : new String[] {io.getFile(), io.getOtherFile()}) {
        if (name == null) continue;
        try {
          Path path = Path.of(name);
          if (!path.isAbsolute()) continue;
          path = path.normalize();
          // Sources may themselves be below the temp/data root. Their own failures stay per-file.
          if (source != null && path.equals(source.toAbsolutePath().normalize())) return false;
          Path candidate = path;
          owned |= roots.stream().anyMatch(candidate::startsWith);
        } catch (InvalidPathException invalid) { /* Unknown provenance needs the canary. */ }
      }
    }
    return owned;
  }
}
