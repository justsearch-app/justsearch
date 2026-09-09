/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.core.context.EngineContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** The one projection from port attribution to the operation-dispatch provenance record. */
public final class EngineProvenance {
  private static final IntentSourceCatalog SOURCES = CoreIntentSourceCatalog.catalog();
  private EngineProvenance() {}

  /** Front-side construction resolves trust from the registered transport, never a client label. */
  public static EngineContext context(EngineContext.ClientKind clientKind, String clientId,
      Optional<String> sessionId, Optional<String> grantReference, TransportTag transport,
      EngineContext.Survival survival, EngineContext.Urgency urgency) {
    return new EngineContext(clientKind, clientId, sessionId, grantReference,
        IntentGateEvaluator.sourceTierFor(SOURCES, transport).name(), transport.name(), survival, urgency);
  }

  /** Internal producers still choose both work axes explicitly. */
  public static EngineContext internal(String owner, EngineContext.Survival survival,
      EngineContext.Urgency urgency) {
    return context(EngineContext.ClientKind.INTERNAL, owner, Optional.empty(), Optional.empty(),
        TransportTag.SYSTEM_INTERNAL, survival, urgency);
  }

  /**
   * Executor identity and signed intent remain dispatch inputs, never inferred from a client label
   * or an opaque grant reference. Registry enums remain authoritative for transport and source tier.
   */
  public static InvocationProvenance invocation(
      EngineContext context, ExecutorTag executor, Instant occurredAt,
      Optional<String> signedIntentToken) {
    Objects.requireNonNull(context, "context");
    sourceTier(context);
    return InvocationProvenance.fromEngineContext(context, executor, occurredAt, signedIntentToken);
  }

  /** Reuse the action ledger's coarse originator projection after catalog validation. */
  public static String originator(EngineContext context) {
    sourceTier(context);
    return io.justsearch.app.observability.ledger.ActionLedgerProjection.originatorOf(
        TransportTag.valueOf(context.transport()));
  }

  /** Resolve through the same catalog as intent gating, rejecting contradictory attribution. */
  public static SourceTier sourceTier(EngineContext context) {
    Objects.requireNonNull(context, "context");
    var transport = TransportTag.valueOf(context.transport());
    var tier = IntentGateEvaluator.sourceTierFor(SOURCES, transport);
    if (SourceTier.valueOf(context.sourceTier()) != tier) {
      throw new IllegalArgumentException("Source tier disagrees with registered transport");
    }
    return tier;
  }
}
