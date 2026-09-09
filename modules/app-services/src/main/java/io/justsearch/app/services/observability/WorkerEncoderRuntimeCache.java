/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

import io.justsearch.core.context.EngineContext;

import io.justsearch.app.api.inference.EncoderRuntimeView;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.ort.EncoderRole;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker-backed {@link EncoderRuntimeCache} (tempdoc 805 G.3).
 *
 * <p>Correlates the Worker's session-policy snapshot with its OrtCuda probe snapshot through {@link
 * EncoderRuntimeExplainer#explainAll} — the same derivation {@code GET /api/inference/encoders}
 * serves, not a second one.
 *
 * <p>Two RPCs back this view, and {@code GET /api/ai/runtime/status} is polled ~1/s while the Brain
 * surface is open, so results are held for {@link #TTL_MS} and the last non-empty map is retained
 * across a failed refresh (a transient RPC failure must not regress a known observation to
 * "unknown"). The client supplier is read live: it is null until the Worker connects.
 */
public final class WorkerEncoderRuntimeCache implements EncoderRuntimeCache {

  private static final Logger log = LoggerFactory.getLogger(WorkerEncoderRuntimeCache.class);

  /** Refresh interval — well under a human-visible delay, well over the FE's 1/s poll. */
  static final long TTL_MS = 2_000L;

  private final Supplier<Map<EncoderRole, EncoderRuntimeView>> fetch;
  private final LongSupplier clock;

  private volatile Map<EncoderRole, EncoderRuntimeView> cached = Map.of();
  private volatile long cachedAtMs;

  /**
   * Freshness is gated on "has a fetch ever happened", not on a sentinel timestamp: with {@code
   * cachedAtMs = Long.MIN_VALUE}, {@code now - cachedAtMs} OVERFLOWS to a large negative number,
   * which passes a {@code < TTL_MS} freshness test forever — the cache would never fetch and every
   * feature would report {@code executionProvider: "unknown"} for the process's whole life. That is
   * exactly the silent-wrong-value class this work exists to close.
   */
  private volatile boolean everFetched;

  public WorkerEncoderRuntimeCache(Supplier<KnowledgeClient> clientSupplier) {
    this(fromClient(clientSupplier), System::currentTimeMillis);
  }

  /** Test seam: arbitrary fetch + clock, so TTL and retention are assertable without a Worker. */
  WorkerEncoderRuntimeCache(
      Supplier<Map<EncoderRole, EncoderRuntimeView>> fetch, LongSupplier clock) {
    this.fetch = fetch;
    this.clock = clock;
  }

  /** The production fetch: the two Worker reads the explainer needs, folded into one derivation. */
  private static Supplier<Map<EncoderRole, EncoderRuntimeView>> fromClient(
      Supplier<KnowledgeClient> clientSupplier) {
    return () -> {
      var engineContext = io.justsearch.app.services.intent.EngineProvenance.internal(
          "encoder-runtime-cache", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
      KnowledgeClient client = clientSupplier == null ? null : clientSupplier.get();
      if (client == null) return Map.of(); // Worker not connected yet.
      return EncoderRuntimeExplainer.explainAll(
          client.getSessionPolicies(engineContext), client.getEncoderOrtCudaViews(engineContext));
    };
  }

  @Override
  public Map<EncoderRole, EncoderRuntimeView> encoderRuntime() {
    long now = clock.getAsLong();
    if (everFetched && now - cachedAtMs < TTL_MS) {
      return cached;
    }
    try {
      Map<EncoderRole, EncoderRuntimeView> derived = fetch.get();
      if (derived != null && !derived.isEmpty()) {
        cached = derived;
      }
    } catch (RuntimeException e) {
      // Best-effort by design: a status read must never fail because the Worker is mid-restart.
      log.debug("Encoder runtime view refresh failed (best-effort): {}", e.toString());
    }
    cachedAtMs = now;
    everFetched = true;
    return cached;
  }
}
