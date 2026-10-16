/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.EngineAdmissionException;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * Task t-20261005-b6d536, FP11: a chat whose retrieval is refused because the Engine's inference
 * threads and queue are full streams an error event with {@code ADMISSION_INFERENCE_BUSY}, its
 * catalogue key and Retry-After seconds, and never offers a safe replay.
 *
 * <p>The reason is looked up by name so the check fails by assertion where it is absent.
 */
final class ConversationInferenceBusyRefusalTest {

  @Test
  void inferenceBusyRefusalStreamsItsOwnCode() {
    var reason = Arrays.stream(EngineAdmissionException.Reason.values())
        .filter(r -> r.name().equals("INFERENCE_BUSY")).findFirst();
    assertTrue(reason.isPresent(), "no INFERENCE_BUSY admission reason");
    var refusal = new EngineAdmissionException(reason.orElseThrow(), 4);

    var event = ConversationEngine.refusalEvent(
        new CompletionException(new ExecutionException(refusal)));

    assertNotNull(event);
    assertEquals("error", event.name());
    assertEquals("ADMISSION_INFERENCE_BUSY", event.payload().get("errorCode"));
    assertEquals("errors.ADMISSION_INFERENCE_BUSY", event.payload().get("i18nKey"));
    assertEquals(4, event.payload().get("retryAfterSeconds"));
    assertEquals(false, event.payload().get("retrySafe"));
  }
}
