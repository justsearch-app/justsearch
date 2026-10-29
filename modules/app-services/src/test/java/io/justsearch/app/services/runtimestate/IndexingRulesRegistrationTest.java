/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D4: the catalog exposes a UI-only rules intent, never an agent/MCP mutator. */
final class IndexingRulesRegistrationTest {
  @Test
  void rulesOperationIsUiOnly() {
    var operation = new CoreOperationCatalog().definitions().stream()
        .filter(op -> op.id().value().equals("core.set-indexing-rules")).findFirst();
    assertTrue(operation.isPresent(), "settings rules need an accepted UI operation");
    assertEquals(Set.of(ExecutorTag.UI), operation.orElseThrow().executors());
  }

}
