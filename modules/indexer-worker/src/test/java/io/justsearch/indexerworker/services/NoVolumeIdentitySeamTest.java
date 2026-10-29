/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #758: the no-volume-identity seam ({@link NoVolumeIdentityExtension})
 * takes the volume identity away, so a folder answers as on a platform without one (Linux,
 * macOS). In a run with the seam's property set, the explicitly registered extension switches it on for this test.
 */
@org.junit.jupiter.api.extension.ExtendWith(NoVolumeIdentityExtension.class)
final class NoVolumeIdentitySeamTest {

  @Test
  void theSeamTakesTheVolumeIdentityAway(@TempDir Path folder) {
    boolean wasOn = RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.get();
    if (Boolean.getBoolean(NoVolumeIdentityExtension.PROPERTY)) {
      assertTrue(wasOn, "a run with the seam's property has the seam on for this test");
    }
    try {
      RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(true);
      assertFalse(NoVolumeIdentityExtension.volumeIdentityAvailable(folder),
          "with the seam on a folder has no volume identity");
    } finally {
      RootIdentity.VOLUME_IDENTITY_UNAVAILABLE.set(wasOn);
    }
  }
}
