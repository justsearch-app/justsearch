/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.Set;

/**
 * Operator-trace surface primitive — the fourth registry primitive alongside
 * {@link Operation}, {@link Resource}, and {@link Prompt}.
 *
 * <p>Per slice 448 + CONFLICT-LEDGER C-012 (path b chosen 2026-05-07): operator-trace
 * surfaces (engine-log, brain-log, OTel spans, audit log) are structurally
 * different from {@link Resource} along five axes (origin, schema, audience, privacy
 * class, self-observability). Modeling them as a sibling primitive avoids per-Resource
 * compensation across all five axes; see slice 446 §A for the canonical reasoning record.
 *
 * <p>Field guidance:
 *
 * <ul>
 *   <li>{@link #id}: namespaced identifier; mirrors {@link OperationRef} format. See
 *       {@link DiagnosticChannelRef}.
 *   <li>{@link #presentation}: i18n-keyed label/description. Resolves through
 *       {@code /api/messages/registry-diagnostic/{locale}}.
 *   <li>{@link #dataClasses}: per-channel default privacy classes the channel may emit
 *       (slice 448 §0 D1). Per-event {@link DataClass} sets may extend this set; consumers
 *       receive the union. Wire shape is {@code Set} not single-valued because the
 *       empirical scan demonstrated lines belong to multiple classes simultaneously.
 *   <li>{@link #producer}: declares whether emissions originate in-process, from a
 *       cross-process gRPC producer, or from an external observer. Forward-compat slots
 *       documented on {@link ProducerKind}.
 *   <li>{@link #deliveryMode}: V1 ships {@link DeliveryMode#SSE_STREAM} only. Reserved
 *       for substrate amendment.
 *   <li>{@link #selector}: per-channel resolver mapping logger namespace prefixes to
 *       {@link SubCategory}. See {@link LoggerNamespaceSelector#defaultEngineLog()}.
 *   <li>{@link #endpoint}: where consumers subscribe. For SSE_STREAM channels this is
 *       the SSE URL.
 *   <li>{@link #consumerPermission}: forward-compat slot per phase-1 review C2 — declares
 *       the consumer-side trust gate. V1 declares; enforcement lands when trust-model
 *       changes ship per slice 448 §B.A.5.
 * </ul>
 *
 * <p>Distinct from {@link Resource} on every load-bearing axis:
 *
 * <ul>
 *   <li>No {@link Category} axis (the {@link SubCategory} axis classifies emission
 *       origin, not information shape).
 *   <li>No {@link SubscriptionMode} matrix; only SSE.
 *   <li>No {@link HistoryPolicy} (operator-trace emissions are firehose-shaped, not
 *       retained-and-queryable).
 *   <li>No {@link Privacy} axis with declared resolver (privacy is per-event
 *       {@code Set<DataClass>}; redaction is consumer-side based on subscription
 *       parameters).
 *   <li>No {@code itemOperations} or {@code primaryKey} (events are not row-keyed).
 * </ul>
 */
public record DiagnosticChannel(
    DiagnosticChannelRef id,
    Presentation presentation,
    Set<DataClass> dataClasses,
    ProducerKind producer,
    DeliveryMode deliveryMode,
    LoggerNamespaceSelector selector,
    String endpoint,
    ConsumerPermission consumerPermission,
    Provenance provenance) implements RegistryEntry {

  public DiagnosticChannel {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(presentation, "presentation");
    Objects.requireNonNull(producer, "producer");
    Objects.requireNonNull(deliveryMode, "deliveryMode");
    Objects.requireNonNull(selector, "selector");
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(consumerPermission, "consumerPermission");
    Objects.requireNonNull(provenance, "provenance");
    dataClasses = dataClasses == null ? Set.of() : Set.copyOf(dataClasses);
    if (endpoint.isBlank()) {
      throw new IllegalArgumentException(
          "DiagnosticChannel " + id.value() + " endpoint must be non-blank");
    }
  }
}
