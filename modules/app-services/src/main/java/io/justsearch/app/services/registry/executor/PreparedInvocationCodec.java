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
final class PreparedInvocationCodec {
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
      if (!descriptor.equals(OperationDescriptor.invocation(descriptor.kind(), descriptor.operationRef(),
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
          || !envelope.descriptor().equals(descriptor)
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

  private void requireKey() {
    if (!cipher.enabled() || cipher.locked()) throw new KeyLockedException();
  }

  private static void checkEnvelopeSize(String value) {
    if (value.getBytes(StandardCharsets.UTF_8).length > OperationPreparedPayload.MAX_ENVELOPE_BYTES) {
      throw new IllegalArgumentException("Prepared envelope is too large");
    }
  }
}
