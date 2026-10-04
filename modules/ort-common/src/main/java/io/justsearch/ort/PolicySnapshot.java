/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.Map;
import java.util.TreeMap;

/**
 * Serialisable snapshot of the resolved policy surface for the
 * {@code GET /api/debug/session-policies} endpoint (tempdoc 397 §7.3 / §7.6).
 *
 * <p>Represents <em>what the resolver would produce given the current resolved config</em>, not
 * runtime state of live sessions. Stage 1 scope: the production path does not yet route through
 * {@link OrtSessionAssembler}, so live sessions may diverge from this snapshot (they won't, since
 * the equivalence test proves parity, but semantically "runtime state" is a separate concept).
 * Stage 4+ may add a live-state view alongside this one.
 *
 * <p>The {@code models} map is deliberately a {@link TreeMap}-backed order. Because
 * {@link EncoderRole} is an enum, both {@code TreeMap} (natural comparator) and Jackson's
 * {@code ORDER_MAP_ENTRIES_BY_KEYS} serialise entries in <em>declaration (ordinal) order</em>,
 * not alphabetical — e.g. {@code EMBEDDING}, {@code BGE_M3}, {@code SPLADE}, {@code NER},
 * {@code RERANKER}, {@code CITATION}. This enables byte-for-byte fixture comparison in
 * {@code PolicySnapshotSerializationTest} and {@code SessionPoliciesContractTest} (368 RC1
 * pattern).
 *
 * @param runtime the single process-wide {@link RuntimePolicy}
 * @param models per-encoder {@link ModelSessionPolicy}, keyed by {@link EncoderRole}
 */
public record PolicySnapshot(RuntimePolicy runtime, Map<EncoderRole, ModelSessionPolicy> models) {

  /** Serializes policy implementation types within their owner for diagnostic consumers. */
  public Serialized toJson() {
    var mapper = new tools.jackson.databind.json.JsonMapper();
    String encodedRuntime = mapper.writeValueAsString(runtime);
    var encodedModels = new java.util.LinkedHashMap<String, String>();
    models.forEach((role, policy) -> encodedModels.put(role.name(), mapper.writeValueAsString(policy)));
    return new Serialized(encodedRuntime, java.util.Collections.unmodifiableMap(encodedModels));
  }

  public record Serialized(String runtime, Map<String, String> models) {}

  public PolicySnapshot {
    // Defensive: ensure deterministic serialization ordering.
    if (!(models instanceof TreeMap)) {
      models = new TreeMap<>(models);
    }
  }
}
