/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/** Inference inputs and their comparison proof stay together through success and failure. */
final class EnrichmentBatch {
  private final IndexingCoordinator coordinator;
  private final Map<String, DocumentFieldOps.EnrichmentInput> inputs;

  EnrichmentBatch(DocumentFieldOps fields, IndexingCoordinator coordinator, List<String> ids)
      throws IOException {
    this.coordinator = coordinator;
    this.inputs = new java.util.LinkedHashMap<>(fields.getEnrichmentInputs(ids));
  }

  EnrichmentBatch(DocumentFieldOps fields, IndexingCoordinator coordinator, List<String> ids,
      long maxContentChars) throws IOException {
    this.coordinator = coordinator;
    this.inputs = fields.getEnrichmentInputs(ids, maxContentChars);
  }

  java.util.Set<String> ids() {
    return inputs.keySet();
  }

  String content(String id) {
    var input = inputs.get(id);
    return input == null ? null : input.content();
  }

  Map<String, String> fields(String id) {
    var input = inputs.get(id);
    return input == null ? Map.of() : input.fields();
  }

  Map<String, String> contents() {
    var result = new HashMap<String, String>();
    inputs.forEach((id, input) -> { if (input.content() != null) result.put(id, input.content()); });
    return result;
  }

  Map<String, Map<String, String>> fields() {
    var result = new HashMap<String, Map<String, String>>();
    inputs.forEach((id, input) -> result.put(id, input.fields()));
    return result;
  }

  boolean update(String id, Map<String, Object> updates) {
    return coordinator.updateDocumentIf(id, inputs.get(id), updates);
  }

  BatchUpdateResult updateBatch(List<Map.Entry<String, Map<String, Object>>> updates) {
    return coordinator.updateDocumentsBatchIf(updates, inputs);
  }

  int failure(String id, String retryField, IntFunction<Map<String, Object>> compute) {
    int previous;
    try { previous = Integer.parseInt(fields(id).getOrDefault(retryField, "0")); }
    catch (NumberFormatException invalid) { previous = 0; }
    var updates = compute.apply(previous);
    boolean applied = update(id, updates);
    return applied && updates.keySet().stream().anyMatch(key -> key.endsWith("_status")) ? 1 : 0;
  }
}
