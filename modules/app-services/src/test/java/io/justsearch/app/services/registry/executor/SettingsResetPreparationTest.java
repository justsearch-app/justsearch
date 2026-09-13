/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationHistoryMode;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.settings.SettingsResetPreparation;
import io.justsearch.core.context.EngineContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettingsResetPreparationTest {
  private static final String FINGERPRINT = "d537ca8993172d84d6299f920a7f90d58f456f0a37f5d274eb9c43939bfe1df3";
  private static final EngineContext CONTEXT = EngineProvenance.internal("settings-reset-test",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);

  @Test
  void actualEnvelopePreservesNormalPairAndRecoveryFingerprint() {
    var expected = new SettingsWitness(4, OperationKeys.generate(Clock.systemUTC()));
    var normal = fixture(SettingsResetPreparation.normal("{}", expected), OperationKind.SETTINGS_APPLY,
        CoreOperationCatalog.RESET_SETTINGS.value(), false, 4L);
    var normalIntent = SettingsResetPreparation.decode(normal.row(), normal.accepted());
    assertEquals(expected, normalIntent.expected());
    assertFalse(normalIntent.recovery());
    assertEquals(4, normalIntent.expectedRevision());
    var recovery = fixture(SettingsResetPreparation.recovery("{ }", FINGERPRINT), OperationKind.SETTINGS_APPLY,
        CoreOperationCatalog.RESET_SETTINGS.value(), false, 0L);
    var recoveryIntent = SettingsResetPreparation.decode(recovery.row(), recovery.accepted());
    assertEquals(FINGERPRINT, recoveryIntent.quarantineFingerprint());
    assertTrue(recoveryIntent.recovery());
    assertEquals(0, recoveryIntent.expectedRevision());
    assertFalse(recovery.accepted().payload().sealed());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"kind", "operation", "undo", "schema", "marker"})
  void validEnvelopeForAnotherOperationModeSchemaOrMarkerCannotAuthorizeReset(String changed) {
    var valid = SettingsResetPreparation.recovery("{}", FINGERPRINT);
    var preparation = changed.equals("schema")
        ? new OperationPreparation("{}", "settings-reset-v2", valid.replayPayloadJson()) : valid;
    var fixture = fixture(preparation, changed.equals("kind") ? OperationKind.OPERATION : OperationKind.SETTINGS_APPLY,
        changed.equals("operation") ? "core.other-reset" : CoreOperationCatalog.RESET_SETTINGS.value(), changed.equals("undo"),
        changed.equals("marker") ? 1L : null);
    assertInvalid(fixture);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "{}",
      "{\"expected\":null,\"quarantineFingerprint\":null}",
      "{\"expected\":{\"acceptedRevision\":0,\"lastCommittedOperationKey\":null},\"quarantineFingerprint\":\"bad\"}",
      "{\"expected\":{\"acceptedRevision\":0.5,\"lastCommittedOperationKey\":null},\"quarantineFingerprint\":null}",
      "{\"expected\":{\"acceptedRevision\":0},\"quarantineFingerprint\":null}",
      "{\"expected\":null,\"quarantineFingerprint\":123}",
      "{\"expected\":null,\"quarantineFingerprint\":\"BAD\"}",
      "{\"expected\":null,\"quarantineFingerprint\":\"bad\",\"untrusted\":\"private marker\"}"
  })
  void malformedOrCoercedIntentCannotBecomeRecoveryAuthority(String payload) {
    assertInvalid(fixture(new OperationPreparation("{}", SettingsResetPreparation.SCHEMA, payload),
        OperationKind.SETTINGS_APPLY, CoreOperationCatalog.RESET_SETTINGS.value(), false, null));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"[]", "null", "{\"confirmed\":true}", "{} {\"confirmed\":true}"})
  void publicFlagsCannotPrepareReset(String arguments) {
    assertThrows(IllegalArgumentException.class, () -> SettingsResetPreparation.recovery(arguments, FINGERPRINT));
    assertThrows(IllegalArgumentException.class, () -> SettingsResetPreparation.normal(arguments, new SettingsWitness(0, null)));
    assertInvalid(fixture(new OperationPreparation(arguments, SettingsResetPreparation.SCHEMA,
        SettingsResetPreparation.recovery("{}", FINGERPRINT).replayPayloadJson()),
        OperationKind.SETTINGS_APPLY, CoreOperationCatalog.RESET_SETTINGS.value(), false, null));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "{\"expected\":{\"acceptedRevision\":0,\"lastCommittedOperationKey\":null},\"expected\":null,\"quarantineFingerprint\":\"" + FINGERPRINT + "\"}",
      "{\"expected\":{\"acceptedRevision\":4,\"acceptedRevision\":0,\"lastCommittedOperationKey\":null},\"quarantineFingerprint\":null}",
      "{\"expected\":null,\"quarantineFingerprint\":\"" + FINGERPRINT + "\"} {}"
  })
  void duplicateFieldsAndTrailingPayloadCannotChangeAcceptedIntent(String payload) {
    assertInvalid(fixture(new OperationPreparation("{}", SettingsResetPreparation.SCHEMA, payload),
        OperationKind.SETTINGS_APPLY, CoreOperationCatalog.RESET_SETTINGS.value(), false, null));
  }

  @Test
  void invalidFactoryWitnessesAndFingerprintsRefuseBeforeEncoding() {
    assertThrows(IllegalArgumentException.class, () -> SettingsResetPreparation.normal("{}",
        new SettingsWitness(Long.MAX_VALUE, OperationKeys.generate(Clock.systemUTC()))));
    assertThrows(IllegalArgumentException.class, () -> SettingsResetPreparation.recovery("{}", FINGERPRINT.toUpperCase(java.util.Locale.ROOT)));
    assertThrows(IllegalArgumentException.class, () -> SettingsResetPreparation.recovery("{}", ""));
    assertThrows(IllegalArgumentException.class, () -> new SettingsResetPreparation.Intent(null, null));
    assertThrows(IllegalArgumentException.class, () -> new SettingsResetPreparation.Intent(new SettingsWitness(0, null), FINGERPRINT));
  }

  private static void assertInvalid(Fixture fixture) {
    var failure = assertThrows(IllegalArgumentException.class,
        () -> SettingsResetPreparation.decode(fixture.row(), fixture.accepted()));
    assertEquals("Invalid accepted settings reset preparation", failure.getMessage());
    assertNull(failure.getCause());
  }

  private static Fixture fixture(OperationPreparation preparation, OperationKind kind, String operation, boolean undo, Long marker) {
    String key = OperationKeys.generate(Clock.systemUTC());
    UUID nonce = UUID.randomUUID();
    var descriptor = OperationDescriptor.invocation(kind, operation, preparation.argumentsJson(), undo);
    var codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var envelope = codec.freeze(key, nonce, descriptor, preparation, CONTEXT,
        EngineProvenance.invocation(CONTEXT, ExecutorTag.UI, Instant.EPOCH, Optional.empty()));
    var row = new OperationRecord(1, key, descriptor, CONTEXT, "UI", null, null, OperationState.RUNNING,
        null, null, 0, 0, 1, 0, 0L, 0, null, null, null, OperationHistoryMode.STANDARD, Instant.EPOCH, marker);
    return new Fixture(row, new OperationStore.Preparation(nonce, codec.encode(envelope)));
  }

  private record Fixture(OperationRecord row, OperationStore.Preparation accepted) {}
}
