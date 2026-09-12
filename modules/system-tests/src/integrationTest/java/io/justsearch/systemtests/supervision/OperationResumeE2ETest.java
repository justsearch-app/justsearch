/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** C2's installed-process fixture grows with the operation acceptance and checkpoint owners. */
@Timeout(7 * 60)
final class OperationResumeE2ETest {
  @Test
  void processingReplayThenRetryDoesNotDuplicateTheDocument() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("operation");
  }
}
