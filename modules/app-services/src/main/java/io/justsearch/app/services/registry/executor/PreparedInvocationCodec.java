/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Seals frozen preparation with the application's existing cipher; never an authority to execute. */
public final class PreparedInvocationCodec {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final StoreCipher cipher;

  PreparedInvocationCodec(StoreCipher cipher) { this.cipher = Objects.requireNonNull(cipher, "cipher"); }

  record Envelope(int version, String key, UUID nonce, OperationDescriptor descriptor,
      OperationPreparation preparation, EngineContext context, ExecutorTag executor, Instant occurredAt) {
    Envelope {
      if (version != 1) throw new IllegalArgumentException("Unsupported preparation version");
      OperationKeys.timestampMillis(key);
      Objects.requireNonNull(nonce, "nonce");
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(preparation, "preparation");
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(executor, "executor");
      Objects.requireNonNull(occurredAt, "occurredAt");
      if (preparation.replaySchema() == null || context.workId().isPresent()) {
        throw new IllegalArgumentException("Preparation must be replayable and unattached");
      }
      String mode = JSON.readTree(descriptor.identityJson()).path("mode").asText();
      if (!mode.equals("invoke") && !mode.equals("undo")) {
        throw new IllegalArgumentException("Invalid preparation mode");
      }
      if (!descriptor.hasSameIdentity(OperationDescriptor.invocation(descriptor.kind(), descriptor.operationRef(),
          preparation.argumentsJson(), mode.equals("undo")))) {
        throw new IllegalArgumentException("Preparation public identity mismatch");
      }
      EngineProvenance.sourceTier(context);
    }

    InvocationProvenance provenance() {
      return EngineProvenance.invocation(context, executor, occurredAt, Optional.empty());
    }

    @Override
    public String toString() { return "PreparedEnvelope[version=" + version + "]"; }
  }

  Envelope freeze(String key, UUID nonce, OperationDescriptor descriptor, OperationPreparation preparation,
      EngineContext context, InvocationProvenance provenance) {
    if (!EngineProvenance.invocation(context, provenance.executor(), provenance.occurredAt(),
        provenance.signedIntentToken()).equals(provenance)) {
      throw new IllegalArgumentException("Preparation provenance mismatch");
    }
    EngineContext detached = new EngineContext(context.clientKind(), context.clientId(), context.sessionId(),
        context.grantReference(), context.sourceTier(), context.transport(), context.survival(), context.urgency());
    return new Envelope(1, key, nonce, descriptor, preparation, detached, provenance.executor(), provenance.occurredAt());
  }

  OperationPreparedPayload encode(Envelope envelope) {
    String json = JSON.writeValueAsString(envelope);
    checkEnvelopeSize(json);
    boolean content = envelope.preparation().content() == OperationPreparation.Content.CONTENT;
    if (!content) return new OperationPreparedPayload(false, json);
    requireKey();
    String stored = cipher.seal(json);
    // StoreCipher permits disabled plaintext for legacy stores; preparation never does.
    if (!cipher.isSealed(stored)) throw new KeyLockedException();
    return new OperationPreparedPayload(true, stored);
  }

  Envelope decode(OperationPreparedPayload stored, String key, UUID nonce, OperationDescriptor descriptor) {
    if (stored.sealed()) {
      requireKey();
      if (!cipher.isSealed(stored.value())) throw new IllegalArgumentException("Unsealed content preparation");
    }
    try {
      String json = stored.sealed() ? cipher.open(stored.value()) : stored.value();
      checkEnvelopeSize(json);
      Envelope envelope = JSON.readValue(json, Envelope.class);
      if (!envelope.key().equals(key) || !envelope.nonce().equals(nonce)
          || !envelope.descriptor().hasSameIdentity(descriptor)
          || stored.sealed() != (envelope.preparation().content() == OperationPreparation.Content.CONTENT)) {
        throw new IllegalArgumentException("Preparation binding mismatch");
      }
      return envelope;
    } catch (KeyLockedException locked) {
      throw locked;
    } catch (RuntimeException malformed) {
      // Jackson/crypto exception text can contain input fragments. Do not expose it as a cause.
      throw new IllegalArgumentException("Invalid persisted preparation");
    }
  }

  /**
   * Decodes the metadata-only preparation used by early recovery before cipher setup.
   *
   * <p>Content-bearing preparations are always sealed and cannot be recovered through this
   * entrypoint. The existing decoder remains the single envelope and binding validator.
   */
  public static OperationPreparation decodeMetadata(OperationPreparedPayload stored, String key,
      UUID nonce, OperationDescriptor descriptor) {
    Objects.requireNonNull(stored, "stored");
    if (stored.sealed()) {
      throw new IllegalArgumentException("Metadata preparation must be unsealed");
    }
    return new PreparedInvocationCodec(StoreCipher.disabled()).decode(stored, key, nonce, descriptor)
        .preparation();
  }

  /** Validate the accepted attribution transition once for all metadata-only recorded producers. */
  public static OperationPreparation decodeAcceptedMetadata(io.justsearch.app.api.operations.OperationRecord row,
      io.justsearch.app.api.operations.OperationStore.Preparation stored) {
    return decodeAcceptedMetadata(row, stored, true);
  }

  /** Reconfigure's accepted intent has no durable continuation grant to decode. */
  public static OperationPreparation decodeAcceptedReconfigureMetadata(
      io.justsearch.app.api.operations.OperationRecord row,
      io.justsearch.app.api.operations.OperationStore.Preparation stored) {
    if (row.descriptor().kind() != io.justsearch.agent.api.registry.OperationKind.RECONFIGURE
        || !"core.reconfigure".equals(row.descriptor().operationRef())) {
      throw new IllegalArgumentException("Reconfigure metadata binding mismatch");
    }
    return decodeAcceptedMetadata(row, stored, false);
  }

  private static OperationPreparation decodeAcceptedMetadata(
      io.justsearch.app.api.operations.OperationRecord row,
      io.justsearch.app.api.operations.OperationStore.Preparation stored, boolean requireAuthorizationBasis) {
    if (stored.payload().sealed()) throw new IllegalArgumentException("Recorded metadata cannot be sealed");
    var envelope = new PreparedInvocationCodec(StoreCipher.disabled()).decode(
        stored.payload(), row.key(), stored.nonce(), row.descriptor());
    var acceptedContext = envelope.context().withGrantReference(row.context().grantReference());
    var preparation = envelope.preparation();
    var provenance = envelope.provenance();
    if (preparation.content() != OperationPreparation.Content.METADATA
        || !acceptedContext.equals(row.context())
        || !envelope.executor().name().equals(row.executor())
        || !Objects.equals(provenance.initiator().orElse(null), row.initiator())
        || !Objects.equals(provenance.correlationId().orElse(null), row.correlationId())
        || !envelope.occurredAt().equals(row.provenanceOccurredAt())) {
      throw new IllegalArgumentException("Recorded ingestion preparation binding mismatch");
    }
    if (requireAuthorizationBasis) {
      io.justsearch.app.api.operations.OperationAuthorizationBasis.decode(
          row.context().grantReference().orElse(null));
    }
    return preparation;
  }

  private void requireKey() {
    if (!cipher.enabled() || cipher.locked()) throw new KeyLockedException();
  }

  private static void checkEnvelopeSize(String value) {
    if (value.getBytes(StandardCharsets.UTF_8).length > OperationPreparedPayload.MAX_ENVELOPE_BYTES) {
      throw new IllegalArgumentException("Prepared envelope is too large");
    }
  }
}
