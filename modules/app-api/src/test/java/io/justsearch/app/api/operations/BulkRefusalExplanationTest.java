/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

final class BulkRefusalExplanationTest {
  @Test
  void everyExplanationCarriesItsOwnSupportCode() {
    for (String operation : List.of("core.bulk-reindex", "core.rebuild-index", "core.activate-installed-models")) {
      for (String code : List.of("cancelled", "RECOVERY_AUTHORIZATION_REFUSED", "RECOVERY_SCOPE_REFUSED",
          "RECOVERY_BINDING_INVALID", "BULK_GENERATION_REFUSED", "BULK_CAPTURE_FAILED",
          "INGEST_RECOVERY_ATTEMPTS_EXHAUSTED", "ACTIVATION_PRECOMMIT_REFUSED")) {
        String message = BulkReindexFailureMessage.forOperation(operation, code);
        assertNotNull(message);
        String detail = " (Code: " + code + ")";
        assertTrue(message.startsWith("The rebuild or migration "), message);
        assertTrue(message.endsWith(detail), message);
        assertEquals(message.indexOf(detail), message.lastIndexOf(detail), message);
      }
    }
  }

  @Test
  void otherOperationsAndUnknownOrActivatedOutcomesGainNoExplanation() {
    assertNull(BulkReindexFailureMessage.forOperation("core.reindex", "BULK_GENERATION_REFUSED"));
    assertNull(BulkReindexFailureMessage.forOperation("core.rebuild-index", "PROMOTED_WITH_GAPS"));
    assertNull(BulkReindexFailureMessage.forOperation("core.bulk-reindex", "BULK_GENERATION_CONFLICT"));
    assertNull(BulkReindexFailureMessage.forOperation("core.bulk-reindex", "FUTURE_CODE"));
    assertNull(BulkReindexFailureMessage.forOperation(null, "BULK_GENERATION_REFUSED"));
    assertNull(BulkReindexFailureMessage.forOperation("core.bulk-reindex", null));
  }
}
