/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.RefusalReason;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec A step 9: every refusal reason has its wire code, and the HTTP body carries the refusal
 * reason and whether a person may load anyway, conforming to api-error-response.v1.
 */
final class ChatRefusalWireTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void everyRefusalReasonMapsToItsWireCodeAndTheBodyConformsToTheSchema() throws Exception {
    for (RefusalReason reason : RefusalReason.values()) {
      var refusal = new ChatRefusal(reason, Optional.of(Duration.ofSeconds(5)), true);
      Map<String, Object> body = ChatResidencyGate.refusalBody(refusal, null, null);
      String expected = switch (reason) {
        case GPU_BUSY, GAME_MODE, RELOAD_BACKOFF, SELF_TEST, BUSY, DEVICE_RECOVERING ->
            "CHAT_UNAVAILABLE";
        case LOADING -> "AI_STARTING";
        case OFF, FAILED, REFUSED -> "AI_OFFLINE";
        case ENGINE_STOPPING -> "SERVICE_UNAVAILABLE";
      };
      assertEquals(expected, body.get("errorCode"), reason.name());
      assertEquals(reason.name(), body.get("reason"));
      assertEquals(true, body.get("forceAllowed"));
      if (!Boolean.TRUE.equals(body.get("retryable"))) {
        assertFalse(body.containsKey("retryAfterMs"), "a permanent code carries no retry time");
      }
      ContractSchemaAssertions.assertConforms(
          "refusal " + reason, "api-error-response.v1.json", JSON.writeValueAsString(body));
    }
  }
}
