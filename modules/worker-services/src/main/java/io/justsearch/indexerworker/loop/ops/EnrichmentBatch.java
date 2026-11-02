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
  private EnrichmentAccounting accounting;
  private final java.util.Set<String> acceptedIds = new java.util.HashSet<>();
  private final Map<String, java.util.Set<String>> correctedFailedStatuses = new HashMap<>();
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

  EnrichmentBatch accounting(EnrichmentAccounting accounting) {
    this.accounting = accounting;
    return this;
  }

  java.util.Set<String> acceptedIds() { return java.util.Set.copyOf(acceptedIds); }

  boolean rejectedOutput(String id, String statusField) {
    return correctedFailedStatuses.getOrDefault(id, java.util.Set.of()).contains(statusField);
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

  void computed(String id, EnrichmentAccounting.Stage stage) {
    if (accounting != null) accounting.computed(id, stage);
  }

  void cancelled(EnrichmentAccounting.Stage stage, Throwable failure) {
    if (accounting != null) accounting.cancelled(stage, failure);
  }

  boolean update(String id, Map<String, Object> updates) {
    if (accounting != null) accounting.computed(id, updates);
    var receipt = inputs.get(id) == null
        ? new io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EnrichmentPublicationReceipt(
            List.of(), List.of(id), null)
        : coordinator.publishEnrichmentBatch(List.of(Map.entry(id, updates)), Map.of(id, inputs.get(id)));
    acceptedIds.addAll(receipt.acceptedIds());
    correctedFailedStatuses.putAll(receipt.correctedFailedStatuses());
    if (accounting != null) accounting.published(List.of(Map.entry(id, updates)), receipt);
    boolean accepted = receipt.result().updatedCount() == 1;
    return accepted;
  }

  BatchUpdateResult updateBatch(List<Map.Entry<String, Map<String, Object>>> updates) {
    if (accounting != null) updates.forEach(entry -> accounting.computed(entry.getKey(), entry.getValue()));
    var receipt = coordinator.publishEnrichmentBatch(updates, inputs);
    acceptedIds.addAll(receipt.acceptedIds());
    correctedFailedStatuses.putAll(receipt.correctedFailedStatuses());
    if (accounting != null) accounting.published(updates, receipt);
    return receipt.result();
  }

  int failure(String id, String retryField, IntFunction<Map<String, Object>> compute) {
    var updates = failureUpdate(id, retryField, compute);
    boolean applied = update(id, updates);
    return applied && updates.keySet().stream().anyMatch(key -> key.endsWith("_status")) ? 1 : 0;
  }

  /** Publishes retry and terminal failures in batches, retaining the actual terminal-write count. */
  int failures(List<String> ids, String retryField, IntFunction<Map<String, Object>> compute) {
    var retryUpdates = new java.util.ArrayList<Map.Entry<String, Map<String, Object>>>();
    var terminalUpdates = new java.util.ArrayList<Map.Entry<String, Map<String, Object>>>();
    for (String id : ids) {
      var updates = failureUpdate(id, retryField, compute);
      var target = updates.keySet().stream().anyMatch(key -> key.endsWith("_status"))
          ? terminalUpdates : retryUpdates;
      target.add(Map.entry(id, updates));
    }
    if (!retryUpdates.isEmpty()) updateBatch(retryUpdates);
    return terminalUpdates.isEmpty() ? 0 : updateBatch(terminalUpdates).updatedCount();
  }

  private Map<String, Object> failureUpdate(String id, String retryField,
      IntFunction<Map<String, Object>> compute) {
    int previous;
    try { previous = Integer.parseInt(fields(id).getOrDefault(retryField, "0")); }
    catch (NumberFormatException invalid) { previous = 0; }
    return compute.apply(previous);
  }
}
