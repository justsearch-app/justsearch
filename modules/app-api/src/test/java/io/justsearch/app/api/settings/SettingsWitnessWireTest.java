/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationKeys;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SettingsWitnessWireTest {
  @Test
  void preparedReceiptProjectsOnlyItsAuthoritativeWitness() {
    String key = OperationKeys.generate(Clock.systemUTC());
    var receipt = new SettingsCommitOwner.Receipt(key, 3,
        OperationResult.success("Prepared", Map.of("witness", new SettingsWitness(0, null))));
    assertEquals(new SettingsWitness(3, key), receipt.response().structuredData().get("witness"));
    assertEquals(3L, receipt.response().structuredData().get("acceptedRevision"));
    assertEquals(key, receipt.response().structuredData().get("operationKey"));
  }

  @Test
  void receiptOnlyWireRoundTripsWithoutInventingSettings() {
    String key = OperationKeys.generate(Clock.systemUTC());
    var response = new SettingsV2(null, null, null, null, new SettingsWitness(3, key), key, "COMPLETE");
    var json = JsonMapper.builder().build();
    var restored = json.readValue(json.writeValueAsString(response), SettingsV2.class);
    assertEquals(response, restored);
    assertNull(restored.ui());
    assertNull(restored.llm());
    assertNull(restored.indexPaths());
  }
  @Test
  void nonNullWitnessRequiresBothFieldsOnInput() {
    var json = JsonMapper.builder().build();
    for (String body : java.util.List.of("{}", "{\"acceptedRevision\":0}",
        "{\"lastCommittedOperationKey\":null}")) {
      assertThrows(RuntimeException.class, () -> json.readValue(body, SettingsWitness.class), body);
    }
    assertEquals(new SettingsWitness(0, null), json.readValue(
        "{\"acceptedRevision\":0,\"lastCommittedOperationKey\":null}", SettingsWitness.class));
  }

}
