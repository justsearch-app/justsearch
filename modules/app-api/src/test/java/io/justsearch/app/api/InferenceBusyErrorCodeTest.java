/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Task t-20261005-b6d536, FP4 and FP11: the refusal for full inference threads and queue has its
 * own admission reason, a transient registry code that the background run's {@code "ADMISSION_" +
 * reason} spelling reaches exactly, and a catalogue message.
 *
 * <p>Looked up by name so the check fails by assertion where the code is absent.
 */
final class InferenceBusyErrorCodeTest {

  @Test
  void inferenceBusyIsATransientRegisteredCodeWithAMessage() throws Exception {
    var reason = Arrays.stream(EngineAdmissionException.Reason.values())
        .filter(r -> r.name().equals("INFERENCE_BUSY")).findFirst();
    assertTrue(reason.isPresent(), "no INFERENCE_BUSY admission reason");
    String spelled = "ADMISSION_" + reason.orElseThrow().name();

    var code = Arrays.stream(ApiErrorCode.values())
        .filter(c -> c.name().equals(spelled)).findFirst();
    assertTrue(code.isPresent(), "no " + spelled + " error code");
    assertEquals(ErrorClass.TRANSIENT, code.orElseThrow().errorClass());

    var messages = new Properties();
    try (var in = InferenceBusyErrorCodeTest.class.getResourceAsStream("/messages/errors.en.properties")) {
      assertNotNull(in);
      messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    String message = messages.getProperty("errors." + spelled);
    assertNotNull(message, "no catalogue message for " + spelled);
    assertTrue(!message.isBlank());
  }
}
