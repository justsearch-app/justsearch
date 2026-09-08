/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.core.context.EngineContext;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class EngineProvenanceTest {
  @Test
  void projectionPreservesAttributionAndNeverTurnsGrantIntoSignedAuthority() {
    for (var transport : TransportTag.values()) {
      var tier = CoreIntentSourceCatalog.catalog().findByTransport(transport)
          .map(io.justsearch.agent.api.registry.IntentSource::sourceTier).orElse(SourceTier.UNTRUSTED);
        var context = new EngineContext(EngineContext.ClientKind.SUPERVISOR, "client",
            Optional.of("session"), Optional.of("opaque-grant"), tier.name(), transport.name(),
            EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND);
        var provenance = EngineProvenance.invocation(context, ExecutorTag.AGENT, Instant.EPOCH,
            Optional.empty());
        assertEquals(transport, provenance.transport());
        assertEquals(ExecutorTag.AGENT, provenance.executor());
        assertEquals(Optional.of("client"), provenance.initiator());
        assertEquals(Optional.of("session"), provenance.correlationId());
        assertEquals(Optional.empty(), provenance.signedIntentToken());
        assertEquals(Instant.EPOCH, provenance.occurredAt());
    }
  }

  @Test
  void mcpCannotClaimTrustedAttribution() {
    var context = new EngineContext(EngineContext.ClientKind.MCP_CLIENT, "client",
        Optional.empty(), Optional.empty(), "TRUSTED", "MCP",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    assertThrows(IllegalArgumentException.class, () -> EngineProvenance.sourceTier(context));
    assertThrows(IllegalArgumentException.class, () -> EngineProvenance.invocation(context,
        ExecutorTag.AGENT, Instant.EPOCH, Optional.empty()));
  }

  @Test
  void anticipatedTransportsRetainTheGateUntrustedFallback() {
    for (var transport : new TransportTag[] {TransportTag.PLUGIN_EMITTED, TransportTag.SCHEDULED,
        TransportTag.RULE_ENGINE}) {
      for (var tier : SourceTier.values()) {
        var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "client",
            Optional.empty(), Optional.empty(), tier.name(), transport.name(),
            EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
        if (tier == SourceTier.UNTRUSTED) assertEquals(tier, EngineProvenance.sourceTier(context));
        else assertThrows(IllegalArgumentException.class, () -> EngineProvenance.sourceTier(context));
      }
    }
  }

  @Test
  void unknownRegistryIdentifiersAreRefusedAtTheProjection() {
    var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "library",
        Optional.empty(), Optional.empty(), "INVENTED_TIER", "SYSTEM_INTERNAL",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
    assertThrows(IllegalArgumentException.class, () -> EngineProvenance.invocation(context,
        ExecutorTag.UI, Instant.EPOCH, Optional.empty()));
  }
}
