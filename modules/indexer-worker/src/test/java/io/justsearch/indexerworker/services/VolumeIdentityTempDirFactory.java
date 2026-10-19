/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.configuration.PlatformPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** UUID-backed Linux fixtures; JUnit retains ownership and removes every generated directory. */
public final class VolumeIdentityTempDirFactory implements TempDirFactory {
  @Override
  public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context)
      throws Exception {
    if (!PlatformPaths.isLinux()) return Files.createTempDirectory("root-volume-");
    Path base = Files.createDirectories(Path.of("build/tmp/root-volume-fixtures").toAbsolutePath());
    return Files.createTempDirectory(base, "root-volume-");
  }

  public static boolean hasLinuxVolumeIdentity(Path directory) throws java.io.IOException {
    return PlatformPaths.isLinux() && RootIdentity.capture(directory).volume() != null;
  }
}
