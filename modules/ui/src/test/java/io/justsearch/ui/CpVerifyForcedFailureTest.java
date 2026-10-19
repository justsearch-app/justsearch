/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.junit.jupiter.api.Test;

/** Throwaway verifier probe: fails on the first workflow attempt only. Never merged. */
final class CpVerifyForcedFailureTest {
  @Test void failsOnFirstAttemptOnly() {
    assertNotEquals("1", System.getenv("GITHUB_RUN_ATTEMPT"), "cp-verify forced app-ui-ui test failure");
  }
}
