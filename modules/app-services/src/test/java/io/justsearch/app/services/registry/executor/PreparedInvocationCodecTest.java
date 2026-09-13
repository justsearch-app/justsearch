/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.app.services.encryption.EncryptionKeystore;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedInvocationCodecTest {
  @TempDir Path directory;
  private static final String ARGUMENTS = "{\"note\":\"private note\"}";
  private static final String TARGET = "{\"target\":\"C:/frozen-note.md\",\"revision\":7}";
  private final String key = OperationKeys.generate(Clock.systemUTC());
  private final UUID nonce = UUID.randomUUID();
  private final OperationDescriptor descriptor = OperationDescriptor.invocation(
      OperationKind.OPERATION, "core.file-note", ARGUMENTS, false);

  private PreparedInvocationCodec.Envelope envelope(PreparedInvocationCodec codec, OperationPreparation.Content content) {
    var context = EngineProvenance.internal("original-author", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND).withWorkId(UUID.randomUUID());
    var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, Instant.EPOCH,
        Optional.of("process-local-intent"));
    String args = content == OperationPreparation.Content.CONTENT ? ARGUMENTS : "{\"path\":\"C:/input\"}";
    var identity = OperationDescriptor.invocation(descriptor.kind(), descriptor.operationRef(), args, false);
    return codec.freeze(key, nonce, identity, new OperationPreparation(args, "note-v1", TARGET, content),
        context, provenance);
  }

  @Test
  void disabledCipherCannotPersistContentAsPlaintext() {
    var codec = new PreparedInvocationCodec(StoreCipher.disabled());
    assertThrows(KeyLockedException.class, () -> codec.encode(envelope(codec, OperationPreparation.Content.CONTENT)));
  }

  @Test
  void configurationChangeDuringSealCannotReturnPlaintext() {
    var state = org.mockito.Mockito.mock(io.justsearch.agent.api.encryption.DataKeyState.class);
    org.mockito.Mockito.when(state.enabled()).thenReturn(true, false);
    var codec = new PreparedInvocationCodec(new StoreCipher(state));
    assertThrows(KeyLockedException.class, () -> codec.encode(envelope(codec, OperationPreparation.Content.CONTENT)));
    org.mockito.Mockito.verify(state, org.mockito.Mockito.never()).dek();
  }

  @Test
  void contentSurvivesActualKeyManagerRestartAndRequiresUnlock() {
    var manager = new DataKeyManager(new EncryptionKeystore(directory));
    manager.setup("test-password".toCharArray());
    var codec = new PreparedInvocationCodec(new StoreCipher(manager));
    var original = envelope(codec, OperationPreparation.Content.CONTENT);
    var stored = codec.encode(original);
    assertTrue(stored.sealed());
    assertFalse(stored.value().contains("private note"));
    assertFalse(stored.value().contains("frozen-note"));
    assertFalse(stored.toString().contains(stored.value()));
    manager.lock();
    assertThrows(KeyLockedException.class, () -> codec.encode(original));
    assertThrows(KeyLockedException.class, () -> codec.decode(stored, key, nonce, descriptor));
    var restarted = new DataKeyManager(new EncryptionKeystore(directory));
    var reopened = new PreparedInvocationCodec(new StoreCipher(restarted));
    assertThrows(KeyLockedException.class, () -> reopened.decode(stored, key, nonce, descriptor));
    restarted.unlock("test-password".toCharArray());
    var restored = reopened.decode(stored, key, nonce, descriptor);
    assertEquals(original, restored);
    assertEquals(TARGET, restored.preparation().replayPayloadJson());
    assertTrue(restored.context().workId().isEmpty());
    assertTrue(restored.provenance().signedIntentToken().isEmpty());
    assertEquals("original-author", restored.provenance().initiator().orElseThrow());
  }

  @Test
  void metadataDoesNotRequireKeyAndNeverPersistsProcessAuthority() {
    var codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var original = envelope(codec, OperationPreparation.Content.METADATA);
    var stored = codec.encode(original);
    assertFalse(stored.sealed());
    assertFalse(stored.value().contains("process-local-intent"));
    assertFalse(original.toString().contains("private note"));
    assertFalse(original.preparation().toString().contains("private note"));
    assertEquals(original, codec.decode(stored, key, nonce, original.descriptor()));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"key", "nonce", "identity"})
  void ciphertextCannotBeReboundToAnotherKeyNonceOrPublicIdentity(String changedField) {
    var manager = new DataKeyManager(new EncryptionKeystore(directory));
    manager.setup("test-password".toCharArray());
    var codec = new PreparedInvocationCodec(new StoreCipher(manager));
    var stored = codec.encode(envelope(codec, OperationPreparation.Content.CONTENT));
    String expectedKey = changedField.equals("key") ? OperationKeys.generate(Clock.systemUTC()) : key;
    UUID expectedNonce = changedField.equals("nonce") ? UUID.randomUUID() : nonce;
    var expectedIdentity = changedField.equals("identity")
        ? OperationDescriptor.invocation(descriptor.kind(), descriptor.operationRef(), "{}", false) : descriptor;
    assertThrows(IllegalArgumentException.class,
        () -> codec.decode(stored, expectedKey, expectedNonce, expectedIdentity));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void preparedModeIsPreservedAndCannotAnswerTheOtherMode(boolean undo) {
    var codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var context = EngineProvenance.internal("test", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
    String args = "{\"executionId\":\"exec-1\"}";
    var identity = OperationDescriptor.invocation(descriptor.kind(), descriptor.operationRef(), args, undo);
    var opposite = OperationDescriptor.invocation(descriptor.kind(), descriptor.operationRef(), args, !undo);
    var original = codec.freeze(key, nonce, identity, new OperationPreparation(args, "v1", "{}"), context,
        EngineProvenance.invocation(context, ExecutorTag.UI, Instant.EPOCH, Optional.empty()));
    var stored = codec.encode(original);
    assertEquals(original, codec.decode(stored, key, nonce, identity));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(stored, key, nonce, opposite));
  }

  @Test
  void malformedFutureAndRelabelledContentRefuseWithoutInputInException() {
    var codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var original = envelope(codec, OperationPreparation.Content.METADATA);
    var stored = codec.encode(original);
    for (String bad : new String[] {"private note", stored.value().replace("\"version\":1", "\"version\":2"),
        stored.value().replace("\"METADATA\"", "\"CONTENT\"")}) {
      var error = assertThrows(IllegalArgumentException.class,
          () -> codec.decode(new OperationPreparedPayload(false, bad), key, nonce, original.descriptor()));
      assertEquals("Invalid persisted preparation", error.getMessage());
      assertNull(error.getCause());
    }
  }

  @Test
  void utf8BoundsApplyBeforeStorageAndMetadataCannotBypassEnvelopeBound() {
    String multibyte = "\u20ac".repeat(70_000);
    assertThrows(IllegalArgumentException.class, () -> new OperationPreparation("{}", "v1", multibyte));
    assertThrows(IllegalArgumentException.class,
        () -> new OperationPreparation("{}", "\u20ac".repeat(43), "{}"));
    assertThrows(IllegalArgumentException.class,
        () -> new OperationPreparedPayload(false, "x".repeat(OperationPreparedPayload.MAX_STORED_BYTES + 1)));
    var codec = new PreparedInvocationCodec(StoreCipher.disabled());
    String hugeArgs = "{\"value\":\"" + "x".repeat(OperationPreparedPayload.MAX_ENVELOPE_BYTES) + "\"}";
    var context = EngineProvenance.internal("test", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
    var prepared = codec.freeze(key, nonce,
        OperationDescriptor.invocation(descriptor.kind(), descriptor.operationRef(), hugeArgs, false),
        new OperationPreparation(hugeArgs, "v1", "{}"), context,
        EngineProvenance.invocation(context, ExecutorTag.UI, Instant.EPOCH, Optional.empty()));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(prepared));
  }
}
