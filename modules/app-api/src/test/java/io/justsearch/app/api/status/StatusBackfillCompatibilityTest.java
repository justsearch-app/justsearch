/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** S33.2: preserve the enrichment shape accepted by already shipped strict clients. */
final class StatusBackfillCompatibilityTest {
  @Test
  void enrichmentDoesNotPublishAnIncompatiblePlacementKey() throws Exception {
    var builder = EnrichmentProgressViewBuilder.builder().backfillMode("idle");
    // Exercise a reported placement on the regressed producer too; null omission alone is not
    // compatibility. A producer without the field has the original, compatible wire shape.
    try {
      builder.getClass().getMethod("backfillGpuBound", Boolean.class).invoke(builder, true);
    } catch (NoSuchMethodException compatibleProducer) {
      // The original API does not expose this field.
    }
    var wire = new ObjectMapper().valueToTree(builder.build());
    assertTrue(wire.has("backfillMode"));
    assertFalse(wire.has("backfillGpuBound"),
        "Old strict clients reject backfillGpuBound and lose the entire status observation");
  }
}
