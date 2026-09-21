/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** C2's installed-process fixture grows with the operation acceptance and checkpoint owners. */
@Timeout(7 * 60)
final class OperationResumeE2ETest {
  @Test
  void recordedOutcomeSurvivesCrashAndProcessingRetryDoesNotDuplicateTheDocument() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("operation");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ingest-before-accept", "settings-before-accept",
      "ingest-after-accept-before-effect", "settings-after-accept-before-effect",
      "ingest-after-effect-before-checkpoint", "settings-after-effect-before-checkpoint",
      "ingest-client-disconnect"})
  void installedOperationSurvivesItsExactFaultBoundary(String scenario) throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario(scenario);
  }
}
