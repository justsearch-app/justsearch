/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/**
 * Links for the removal tests on every platform: a folder link is a junction on Windows (it needs
 * no privilege, and must be created) and a symbolic link elsewhere; a file link is a symbolic link,
 * and a platform that refuses one skips that case. Every link is removed by {@link #close()}, so a
 * temporary-folder cleanup never walks through one into its target.
 */
final class RemovalLinks implements AutoCloseable {
  private final List<Path> created = new ArrayList<>();

  static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** Whether POSIX permissions can be set on {@code path}'s file system. */
  static boolean posix(Path path) {
    return path.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  /** {@code link} becomes a link to the folder {@code target}. */
  Path folder(Path link, Path target) throws Exception {
    if (windows()) {
      Process maker = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(),
          target.toString()).redirectErrorStream(true).start();
      String output = new String(maker.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(maker.waitFor(30, TimeUnit.SECONDS), "junction creation timed out");
      assertEquals(0, maker.exitValue(), output);
    } else {
      Files.createSymbolicLink(link, target);
    }
    created.add(link);
    return link;
  }

  /** {@code link} becomes a symbolic link to the file {@code target}, where the platform allows. */
  Path file(Path link, Path target) throws Exception {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException refused) {
      Assumptions.abort("This platform refuses file symbolic links here: " + refused);
    }
    created.add(link);
    return link;
  }

  /** Removes every link made here (the link itself, never what it points at). */
  @Override
  public void close() throws IOException {
    for (int i = created.size() - 1; i >= 0; i--) {
      Path link = created.get(i);
      if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.delete(link);
    }
    created.clear();
  }
}
