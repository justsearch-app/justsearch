/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.operations.ForgetService;
import io.justsearch.app.api.operations.OperationKeys;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ForgetDecisionValidationTest {
  @Test
  void frozenTargetRetainsStrictValidationWhenUsingTheSharedMapper(@TempDir Path directory) {
    var handler = new ForgetDecisionHandler(ForgetService::unavailable, ForgetDecisionHandler.Decision.RETRY);
    String key = HandlerJson.MAPPER.writeValueAsString(OperationKeys.generate(Clock.systemUTC()));
    String path = HandlerJson.MAPPER.writeValueAsString(directory.resolve("saved.txt").toString());
    String arguments = "{\"forgetKey\":" + key + "}";
    String payload = "{\"forgetKey\":" + key + ",\"path\":" + path + ",\"scope\":\"FILE\"}";
    assertDoesNotThrow(() -> handler.validatePreparation(prepared(arguments, payload)));
    for (String invalid : List.of("null", payload + " {}",
        "{\"forgetKey\":" + key + ",\"forgetKey\":" + key + ",\"path\":" + path + ",\"scope\":\"FILE\"}",
        "{\"forgetKey\":" + key + ",\"path\":" + path + ",\"scope\":\"FILE\",\"extra\":true}",
        "{\"forgetKey\":" + key + ",\"scope\":\"FILE\"}",
        "{\"forgetKey\":7,\"path\":" + path + ",\"scope\":\"FILE\"}",
        "{\"forgetKey\":" + key + ",\"path\":7,\"scope\":\"FILE\"}",
        "{\"forgetKey\":" + key + ",\"path\":" + path + ",\"scope\":0}",
        "{\"forgetKey\":" + key + ",\"path\":" + path + ",\"scope\":\"file\"}")) {
      assertThrows(RuntimeException.class, () -> handler.validatePreparation(prepared(arguments, invalid)), invalid);
    }
    for (String invalid : List.of(arguments + " {}", "{\"forgetKey\":7}",
        "{\"forgetKey\":" + key + ",\"forgetKey\":" + key + "}",
        "{\"forgetKey\":" + key + ",\"extra\":true}")) {
      assertThrows(RuntimeException.class, () -> handler.validatePreparation(prepared(invalid, payload)), invalid);
    }
  }

  private static OperationPreparation prepared(String arguments, String payload) {
    return new OperationPreparation(arguments, "forget-decision.v1", payload);
  }
}
