/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@EnabledOnOs(OS.LINUX)
@ResourceLock("SYSTEM_PROPERTIES")
class LinuxRootUnsupportedArchitectureTest {
  @TempDir Path root;

  @Test void unsupportedAbiKeepsPortableWitnessWithoutGuessingOpenFlagsOrVolume() throws Exception {
    String arch = System.getProperty("os.arch");
    try {
      System.setProperty("os.arch", "aarch64");
      try (var lease = RootIdentity.hold(root)) {
        assertInstanceOf(RootIdentity.ProviderFileIdentity.class, lease.identity().stableFileIdentity());
        assertNull(lease.identity().volume());
        lease.requireCurrent();
      }
    } finally { System.setProperty("os.arch", arch); }
  }

  @Test void unsupportedAbiAlsoWorksWithExplicitlyUnavailableVolume() throws Exception {
    String arch = System.getProperty("os.arch");
    var evidence = NoVolumeIdentityExtension.withVolumeIdentity(false);
    try (evidence) {
      System.setProperty("os.arch", "aarch64");
      try (var lease = RootIdentity.hold(root)) {
        assertInstanceOf(RootIdentity.ProviderFileIdentity.class, lease.identity().stableFileIdentity());
        assertNull(lease.identity().volume());
        lease.requireCurrent();
      }
    } finally { System.setProperty("os.arch", arch); }
  }
}
