/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.settings.SettingsWitness;
import org.junit.jupiter.api.Test;

class RuntimeIntentPreparationTest {
  private static final String KEY = "0194f72c-0000-7000-8000-000000000001";

  @Test
  void roundTripsZeroRevisionAndPreservesRawPublicArguments() {
    String arguments = "{\"enabled\":true,\"mode\":\"headless\"}";
    var expected = new SettingsWitness(0, null);

    OperationPreparation prepared = RuntimeIntentPreparation.prepare(arguments, expected);

    assertEquals(arguments, prepared.argumentsJson());
    assertEquals(RuntimeIntentPreparation.SCHEMA, prepared.replaySchema());
    assertEquals(OperationPreparation.Content.METADATA, prepared.content());
    assertEquals("{\"acceptedRevision\":0,\"lastCommittedOperationKey\":null}",
        prepared.replayPayloadJson());
    assertEquals(expected, RuntimeIntentPreparation.validate(prepared));
  }

  @Test
  void roundTripsPositiveRevisionAndCanonicalWitnessKey() {
    var expected = new SettingsWitness(4, KEY);

    OperationPreparation prepared = RuntimeIntentPreparation.prepare("raw caller input", expected);

    assertEquals(expected, RuntimeIntentPreparation.validate(prepared));
  }

  @Test
  void callerArgumentsAreNotParsedOrNormalized() {
    String arguments = "not-json and intentionally owned by the caller";

    OperationPreparation prepared = RuntimeIntentPreparation.prepare(arguments,
        new SettingsWitness(0, null));

    assertEquals(arguments, prepared.argumentsJson());
  }

  @Test
  void passthroughWrongSchemaAndContentAreRejected() {
    var valid = RuntimeIntentPreparation.prepare("{}", new SettingsWitness(0, null));

    assertInvalid(OperationPreparation.passthrough("{}"));
    assertInvalid(new OperationPreparation("{}", "settings-runtime-intent-v2",
        valid.replayPayloadJson()));
    assertInvalid(new OperationPreparation("{}", RuntimeIntentPreparation.SCHEMA,
        valid.replayPayloadJson(), OperationPreparation.Content.CONTENT));
    assertInvalid(null);
  }

  @Test
  void malformedTypesFieldsAndTrailingTokensAreRejected() {
    String[] payloads = {
      "{}",
      "[]",
      "null",
      "{\"acceptedRevision\":0.5,\"lastCommittedOperationKey\":null}",
      "{\"acceptedRevision\":\"0\",\"lastCommittedOperationKey\":null}",
      "{\"acceptedRevision\":0,\"lastCommittedOperationKey\":123}",
      "{\"acceptedRevision\":-1,\"lastCommittedOperationKey\":null}",
      "{\"acceptedRevision\":1,\"lastCommittedOperationKey\":null}",
      "{\"acceptedRevision\":0,\"lastCommittedOperationKey\":\"" + KEY + "\"}",
      "{\"acceptedRevision\":1,\"lastCommittedOperationKey\":\"not-a-uuid\"}",
      "{\"acceptedRevision\":1,\"lastCommittedOperationKey\":\""
          + "00000000-0000-4000-8000-000000000001\"}",
      "{\"acceptedRevision\":0,\"lastCommittedOperationKey\":null,\"enabled\":true}",
      "{\"acceptedRevision\":0,\"lastCommittedOperationKey\":null} {}"
    };
    for (String payload : payloads) {
      assertInvalid(new OperationPreparation("{}", RuntimeIntentPreparation.SCHEMA, payload));
    }
  }

  @Test
  void duplicatePayloadFieldsCannotChangeWitness() {
    String[] payloads = {
      "{\"acceptedRevision\":0,\"acceptedRevision\":4,\"lastCommittedOperationKey\":null}",
      "{\"acceptedRevision\":0,\"lastCommittedOperationKey\":null,\"lastCommittedOperationKey\":\""
          + KEY + "\"}"
    };
    for (String payload : payloads) {
      assertInvalid(new OperationPreparation("{}", RuntimeIntentPreparation.SCHEMA, payload));
    }
  }

  @Test
  void maxRevisionAndInvalidFactoryWitnessAreRefused() {
    assertThrows(IllegalArgumentException.class,
        () -> RuntimeIntentPreparation.prepare("{}",
            new SettingsWitness(Long.MAX_VALUE, KEY)));
    assertInvalid(new OperationPreparation("{}", RuntimeIntentPreparation.SCHEMA,
        "{\"acceptedRevision\":9223372036854775807,\"lastCommittedOperationKey\":\""
            + KEY + "\"}"));
    assertInvalid(new OperationPreparation("{}", RuntimeIntentPreparation.SCHEMA,
        "{\"acceptedRevision\":9223372036854775808,\"lastCommittedOperationKey\":null}"));
  }

  private static void assertInvalid(OperationPreparation preparation) {
    var failure = assertThrows(IllegalArgumentException.class,
        () -> RuntimeIntentPreparation.validate(preparation));
    assertEquals("Invalid runtime intent preparation", failure.getMessage());
    assertNull(failure.getCause());
  }
}
