/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.supervision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.api.chat.ChatReasonCodes;
import io.justsearch.app.api.chat.FailedCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/*
 * Spec A step 7: the brain rows of governance/supervision-contract.v1.json say which FAILED code a
 * chat failure ends in. The generative component publishes that failure under its own reason code
 * (ChatReasonCodes.forFailure), so every FAILED code a row names must come with the reason code the
 * status surfaces show for it, and the classified failures must all be named.
 */
final class ChatFailedReasonCodesContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /* The failures Spec A §7.1 classifies with a code of their own. */
  private static final Set<FailedCode> CLASSIFIED = EnumSet.of(FailedCode.RESOURCE_EXHAUSTED,
      FailedCode.STARTUP_TIMEOUT, FailedCode.START_FAILED, FailedCode.MODEL_LOAD_FAILED);

  @Test
  void everyFailedCodeTheBrainRowsNameCarriesItsReasonCode() throws IOException {
    List<String> failures = new ArrayList<>();
    Set<FailedCode> named = EnumSet.noneOf(FailedCode.class);
    for (JsonNode mode : brain().get("faultModes")) {
      String terminal = mode.path("terminalState").asText();
      for (FailedCode code : FailedCode.values()) {
        if (!terminal.contains(code.name().toLowerCase(Locale.ROOT))) continue;
        named.add(code);
        String reason = ChatReasonCodes.forFailure(code).code();
        if (!terminal.contains(reason)) {
          failures.add(mode.path("mode").asText() + ": names " + code.name().toLowerCase(Locale.ROOT)
              + " but not the reason code it is published as (" + reason + ")");
        }
      }
    }
    if (!failures.isEmpty()) {
      fail("brain FAILED rows out of step with ChatReasonCodes:\n  "
          + String.join("\n  ", failures));
    }
    Set<FailedCode> missing = EnumSet.copyOf(CLASSIFIED);
    missing.removeAll(named);
    assertEquals(Set.of(), missing, "every classified failure has a FAILED row");
  }

  private static JsonNode brain() throws IOException {
    JsonNode register = MAPPER.readTree(repoRoot()
        .resolve("governance/supervision-contract.v1.json").toFile());
    for (JsonNode process : register.get("processes")) {
      if ("brain".equals(process.get("id").asText())) return process;
    }
    throw new IllegalStateException("no brain process in the supervision contract");
  }

  private static Path repoRoot() {
    Path p = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 10 && p != null; i++) {
      if (Files.exists(p.resolve("governance/supervision-contract.v1.json"))) return p;
      p = p.getParent();
    }
    throw new IllegalStateException("repo root not found from " + Paths.get("").toAbsolutePath());
  }
}
