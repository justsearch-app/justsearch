/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EnrichmentPublicationReceipt;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnrichmentStorageDispositionTest {
  @Test void unreadableArtifactDefersEarnedOutcomeWithoutRetryCharge() {
    var accounting = new EnrichmentAccounting();
    accounting.select(List.of("broken", "good"), EnrichmentAccounting.Stage.NER);
    Map<String, Object> outcome = Map.of(SchemaFields.NER_STATUS, "COMPLETED_EMPTY",
        SchemaFields.NER_RETRY_COUNT, "0");
    var updates = List.of(Map.entry("broken", outcome), Map.entry("good", outcome));
    updates.forEach(entry -> accounting.computed(entry.getKey(), entry.getValue()));
    accounting.published(updates, new EnrichmentPublicationReceipt(
        List.of("good"), List.of(), Map.of(), Map.of("broken", "sparse_artifact_read"), null));
    var summary = accounting.snapshot();
    assertEquals(1L, summary.counters().get("ner.deferred_storage"));
    assertEquals(1, summary.count(EnrichmentAccounting.Stage.NER, EnrichmentAccounting.Disposition.DEFERRED));
    assertEquals(1, summary.count(EnrichmentAccounting.Stage.NER, EnrichmentAccounting.Disposition.COMPLETED_EMPTY));
    assertEquals(0, summary.count(EnrichmentAccounting.Stage.NER, EnrichmentAccounting.Disposition.FAILED_CHARGED));
    assertEquals(2, summary.tickets().size());
    assertEquals(1L, summary.counters().get("ner.accepted"));
    assertEquals(2L, summary.counters().get("ner.computed"));
  }
}
