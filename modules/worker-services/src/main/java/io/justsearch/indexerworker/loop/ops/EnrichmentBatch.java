/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.inference.LocalSessionAcquisition;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.BooleanSupplier;

/** Inference inputs and their comparison proof stay together through success and failure. */
final class EnrichmentBatch {
  private final IndexingCoordinator coordinator;
  private final IndexingCoordinator.EnrichmentAuthority authority;
  private final long deletionEpoch;
  private EnrichmentAccounting accounting;
  private final EnrichmentSelection selection;
  private final Map<String, DocumentFieldOps.EnrichmentInput> inputs;
  private final java.util.Set<String> acceptedIds = new java.util.HashSet<>();

  EnrichmentBatch(DocumentFieldOps fields, IndexingCoordinator coordinator, List<String> ids)
      throws IOException {
    this(fields, coordinator, ids, coordinator.enrichmentAuthority(), null, java.util.Set.of());
  }

  EnrichmentBatch(DocumentFieldOps fields, IndexingCoordinator coordinator, List<String> ids,
      IndexingCoordinator.EnrichmentAuthority authority, EnrichmentSelection selection,
      java.util.Set<String> excluded) throws IOException {
    this.selection = selection;
    this.coordinator = coordinator;
    this.authority = authority;
    this.deletionEpoch = coordinator.bulkDeleteEpoch();
    Map<String, DocumentFieldOps.EnrichmentInput> captured;
    if (selection == null) {
      captured = fields.getEnrichmentInputs(ids, EmbeddingBackfillOps.MAX_PARENT_BATCH_CHARS);
    } else {
      var page = fields.getEnrichmentInputPage(ids, EmbeddingBackfillOps.MAX_PARENT_BATCH_CHARS);
      captured = page.inputs();
      selection.collected(captured, page.examinedIds(), excluded);
    }
    // Readers provide a map, but the selected keyset prefix owns processing order.
    var ordered = new java.util.LinkedHashMap<String, DocumentFieldOps.EnrichmentInput>();
    for (String id : ids) {
      var input = captured.get(id);
      if (input != null) ordered.put(id, input);
    }
    inputs = java.util.Collections.unmodifiableMap(ordered);
  }

  java.util.Set<String> acceptedIds() { return acceptedIds; }

  boolean storageFailed() { return accounting != null && accounting.storageFailed(); }

  EnrichmentBatch accounting(EnrichmentAccounting accounting) {
    this.accounting = accounting;
    return this;
  }

  ScopedValue.Carrier inferenceCancellation(BooleanSupplier stop, BooleanSupplier unavailable) {
    return LocalSessionAcquisition.withCancellation(
        () -> hardStop(stop, coordinator, deletionEpoch, authority) || unavailable.getAsBoolean());
  }

  boolean stopped(BooleanSupplier stop) {
    rejectRetiredOutcomes();
    return hardStop(stop, coordinator, deletionEpoch, authority);
  }

  boolean stopped(BooleanSupplier stop, BooleanSupplier unavailable,
      EnrichmentAccounting.Stage... stages) {
    boolean stopped = stopped(stop);
    // The stop supplier may pace, so observe retirement after it returns too.
    return retired(unavailable, stages) || stopped;
  }

  /** Availability is checked at unit boundaries; replacement is also fenced at publication. */
  boolean retired(BooleanSupplier unavailable, EnrichmentAccounting.Stage... stages) {
    boolean unavailableNow = unavailable.getAsBoolean();
    rejectRetiredOutcomes();
    if (unavailableNow && accounting != null) {
      for (var stage : stages) accounting.retired(stage);
    }
    return unavailableNow || coordinator.enrichmentAuthority() != authority;
  }

  static boolean hardStop(BooleanSupplier stop, IndexingCoordinator coordinator, long deletionEpoch,
      IndexingCoordinator.EnrichmentAuthority authority) {
    return stop.getAsBoolean() || Thread.currentThread().isInterrupted()
        || (coordinator != null && (coordinator.bulkDeleteEpoch() != deletionEpoch
            || coordinator.enrichmentAuthority() != authority));
  }

  private io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EnrichmentPublicationReceipt publish(
      List<Map.Entry<String, Map<String, Object>>> updates) {
    try {
      return authority == null ? coordinator.publishEnrichmentBatch(updates, inputs)
          : coordinator.publishEnrichmentBatch(updates, inputs, authority);
    } catch (RuntimeException failure) {
      // A closed writer can fail before entering its ordered mutation loop.
      return new io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EnrichmentPublicationReceipt(
          List.of(), List.of(), failure);
    }
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
    if (selection != null) selection.examined(id, stage);
    if (accounting != null) accounting.computed(id, stage);
  }

  void started(List<String> ids, EnrichmentAccounting.Stage stage) {
    if (selection != null) ids.forEach(id -> selection.examined(id, stage));
    if (accounting != null) ids.forEach(id -> accounting.started(id, stage));
  }

  void retired(EnrichmentAccounting.Stage stage) {
    if (accounting != null) accounting.retired(stage);
  }

  void rejectRetiredOutcomes() {
    if (accounting != null && coordinator.enrichmentAuthority() != authority) {
      accounting.rejectedRetiredOutcomes();
    }
  }

  boolean cancelled(EnrichmentAccounting.Stage stage, Throwable failure) {
    return accounting != null && accounting.cancelled(stage, failure);
  }

  private void examined(String id, Map<String, Object> updates) {
    if (selection == null) return;
    for (var stage : EnrichmentAccounting.Stage.values()) {
      if (updates.containsKey(stage.status) || updates.containsKey(stage.retry)) selection.examined(id, stage);
    }
  }

  boolean update(String id, Map<String, Object> updates) {
    examined(id, updates);
    if (accounting != null) accounting.computed(id, updates);
    var receipt = inputs.get(id) == null
        ? new io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EnrichmentPublicationReceipt(
            List.of(), List.of(id), null)
        : publish(List.of(Map.entry(id, updates)));
    if (accounting != null) accounting.published(List.of(Map.entry(id, updates)), receipt);
    acceptedIds.addAll(receipt.acceptedIds());
    rejectRetiredOutcomes();
    boolean accepted = receipt.result().updatedCount() == 1;
    return accepted;
  }

  BatchUpdateResult updateBatch(List<Map.Entry<String, Map<String, Object>>> updates) {
    updates.forEach(entry -> examined(entry.getKey(), entry.getValue()));
    if (accounting != null) updates.forEach(entry -> accounting.computed(entry.getKey(), entry.getValue()));
    var receipt = publish(updates);
    acceptedIds.addAll(receipt.acceptedIds());
    if (accounting != null) accounting.published(updates, receipt);
    rejectRetiredOutcomes();
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
