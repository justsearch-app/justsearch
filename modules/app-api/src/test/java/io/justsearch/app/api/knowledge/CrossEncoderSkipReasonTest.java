/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Register F-052 (production side): the cross-encoder skip vocabulary is closed.
 *
 * <p>Before this enum the Head wrote raw strings into the {@code cross-encoder} trace stage and
 * passed the Worker's {@code RerankResponse.skip_reason} through verbatim — so an unrecognised
 * Worker string reached the FE as an unworded code, and a deadline drop (the empty-reason case)
 * was the only one normalised at all.
 */
@DisplayName("CrossEncoderSkipReason: closed vocabulary for the cross-encoder trace stage")
class CrossEncoderSkipReasonTest {

  // Register F-054: an unstated cause is UNKNOWN, not a guessed deadline. The Worker now names
  // every skip it reports, so blank means "the Worker could not say" — and guessing "deadline" is
  // the same mislabel, one layer up, that F-054 removes from the Worker.
  @Test
  @DisplayName("an empty Worker skip reason is UNKNOWN, not a guessed deadline")
  void blankWorkerReasonIsUnknown() {
    assertEquals(CrossEncoderSkipReason.UNKNOWN, CrossEncoderSkipReason.fromWorkerSkipReason(""));
    assertEquals(CrossEncoderSkipReason.UNKNOWN, CrossEncoderSkipReason.fromWorkerSkipReason(null));
  }

  @Test
  @DisplayName("the Worker's declared skip reasons map to their members")
  void knownWorkerReasonsMap() {
    assertEquals(
        CrossEncoderSkipReason.DEADLINE_EXCEEDED,
        CrossEncoderSkipReason.fromWorkerSkipReason("DEADLINE_EXCEEDED"));
    assertEquals(
        CrossEncoderSkipReason.MODEL_NOT_LOADED,
        CrossEncoderSkipReason.fromWorkerSkipReason("MODEL_NOT_LOADED"));
    assertEquals(
        CrossEncoderSkipReason.INFERENCE_FAILED,
        CrossEncoderSkipReason.fromWorkerSkipReason("INFERENCE_FAILED"));
  }

  @Test
  @DisplayName("F-054: an inference failure is a drop, and is not the deadline code")
  void inferenceFailureIsItsOwnDrop() {
    CrossEncoderSkipReason reason =
        CrossEncoderSkipReason.fromWorkerSkipReason("INFERENCE_FAILED");
    assertTrue(reason.isDrop());
    assertNotEquals(CrossEncoderSkipReason.DEADLINE_EXCEEDED, reason);
    assertEquals("INFERENCE_FAILED", reason.wire());
  }

  @Test
  @DisplayName("an unrecognised Worker string resolves to UNKNOWN, never a raw pass-through")
  void unrecognisedWorkerReasonIsUnknown() {
    assertEquals(
        CrossEncoderSkipReason.UNKNOWN,
        CrossEncoderSkipReason.fromWorkerSkipReason("SOME_FUTURE_WORKER_CODE"));
  }

  @Test
  @DisplayName("the drop class is exactly the reasons the user tier words")
  void dropClassIsDeclared() {
    for (CrossEncoderSkipReason r : CrossEncoderSkipReason.values()) {
      boolean expectedDrop =
          switch (r) {
            case DEADLINE_EXCEEDED,
                SEARCH_DEADLINE_EXCEEDED,
                RPC_FAILED,
                MODEL_NOT_LOADED,
                INFERENCE_FAILED,
                UNKNOWN -> true;
            case NAVIGATIONAL_QUERY,
                DISABLED,
                BELOW_MIN_THRESHOLD,
                DOCS_TOO_LONG,
                PIPELINE_NOT_ELIGIBLE,
                MODEL_NOT_CONFIGURED,
                FUSION_CONFIDENT -> false;
          };
      assertEquals(expectedDrop, r.isDrop(), r.name());
    }
  }

  @Test
  @DisplayName("wire() is the enum name — the string the trace stage carries")
  void wireIsName() {
    for (CrossEncoderSkipReason r : CrossEncoderSkipReason.values()) {
      assertEquals(r.name(), r.wire());
      assertTrue(r.wire().matches("[A-Z][A-Z0-9_]*"), r.wire());
    }
  }
}
