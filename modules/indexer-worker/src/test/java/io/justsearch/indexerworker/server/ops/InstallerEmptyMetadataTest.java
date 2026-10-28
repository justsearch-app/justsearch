/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class InstallerEmptyMetadataTest {
  private static final Logger LOG = LoggerFactory.getLogger(InstallerEmptyMetadataTest.class);

  @Test
  void installerEmptyProofOmitsOnlyTheMissingStamp() throws Exception {
    var data = Map.of("build_state", "COMPLETE", "index_fingerprint", "exact-b");
    assertTrue(verify(data), "a proven empty installer B needs no invented embedding stamp");
    assertFalse(KnowledgeServerMigrationOps.verifyGreenMetadata(data, "exact-b", "model-b", LOG),
        "ordinary migration still needs its stamp");
  }

  @Test
  void emptyProofRetainsTargetCompletionAndContradictionChecks() throws Exception {
    for (String invalid : new String[] {"missing-target", "wrong-target", "building", "missing-build", "wrong-model"}) {
      var data = new HashMap<>(Map.of("build_state", "COMPLETE", "index_fingerprint", "exact-b"));
      switch (invalid) {
        case "missing-target" -> data.remove("index_fingerprint");
        case "wrong-target" -> data.put("index_fingerprint", "other-b");
        case "building" -> data.put("build_state", "BUILDING");
        case "missing-build" -> data.remove("build_state");
        case "wrong-model" -> data.put("embedding_model_sha256", "model-a");
      }
      assertFalse(verify(data), invalid);
    }
  }

  private static boolean verify(Map<String, String> data) throws Exception {
    try {
      var method = KnowledgeServerMigrationOps.class.getDeclaredMethod("verifyGreenMetadata",
          Map.class, String.class, String.class, boolean.class, Logger.class);
      return (boolean) method.invoke(null, data, "exact-b", "model-b", true, LOG);
    } catch (NoSuchMethodException baseline) {
      return KnowledgeServerMigrationOps.verifyGreenMetadata(data, "exact-b", "model-b", LOG);
    }
  }
}
