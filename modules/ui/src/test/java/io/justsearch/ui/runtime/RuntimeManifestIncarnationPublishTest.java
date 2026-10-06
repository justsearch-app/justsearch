/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.app.api.runtime.RuntimeManifest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec F §3.2: the Engine copies {@code JUSTSEARCH_ENGINE_INCARNATION} into the manifest from the
 * first write on, so a census binds the manifest to the run that started this Engine.
 */
final class RuntimeManifestIncarnationPublishTest {
  @TempDir Path dataDir;

  @Test
  void manifestCarriesTheIncarnationFromTheFirstWrite() throws Exception {
    var incarnation = new RuntimeManifest.IncarnationInfo("run-census", 2);
    try (var publisher = new RuntimeManifestPublisher(
        dataDir, new MutableManagedChildRegistry(), incarnation)) {
      publisher.publishOwnershipSeed();
      var mapper = JsonMapper.builder().build();
      JsonNode seeded = mapper.readTree(publisher.manifestPath().toFile());
      assertEquals("run-census", seeded.path("incarnation").path("session").asString());
      assertEquals(2, seeded.path("incarnation").path("n").asInt());

      RuntimeManifest later = publisher.publishHead(43124, "token");
      assertEquals(incarnation, later.incarnation(), "a later publish carries it forward");
      assertEquals(incarnation, later.publicProjection().incarnation());
    }
  }

  @Test
  void unsupervisedEngineWritesNoIncarnation() throws Exception {
    try (var publisher = new RuntimeManifestPublisher(
        dataDir, new MutableManagedChildRegistry(), null)) {
      publisher.publishOwnershipSeed();
      JsonNode seeded = JsonMapper.builder().build().readTree(publisher.manifestPath().toFile());
      assertFalse(seeded.has("incarnation"));
    }
  }
}
