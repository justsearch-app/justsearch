/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec F §1.3: the runtime manifest says whether this Engine's helpers end with it, from the first
 * write on, and every later publish carries it.
 */
final class RuntimeManifestContainmentTest {
  @TempDir Path dataDir;

  @Test
  void manifestCarriesTheBootContainmentFromTheFirstWrite() throws Exception {
    // A test JVM never installs the Engine job, so this is UNAVAILABLE / not_installed here.
    EngineContainment boot = EngineContainment.current();
    var expected = new RuntimeManifest.ContainmentInfo(
        boot.status().name(), boot.unavailableReason().orElse(null));
    try (var publisher = new RuntimeManifestPublisher(dataDir)) {
      publisher.publishOwnershipSeed();
      var mapper = JsonMapper.builder().build();
      JsonNode seeded = mapper.readTree(publisher.manifestPath().toFile());
      assertEquals(expected,
          mapper.treeToValue(seeded.get("containment"), RuntimeManifest.ContainmentInfo.class),
          "the first write on disk carries the containment block");

      RuntimeManifest later = publisher.publishHead(43123, "token");
      assertEquals(expected, later.containment(), "a later publish carries it forward");
      assertEquals(later.containment(), later.publicProjection().containment(),
          "the public projection keeps it: it carries no credential");
    }
  }
}
