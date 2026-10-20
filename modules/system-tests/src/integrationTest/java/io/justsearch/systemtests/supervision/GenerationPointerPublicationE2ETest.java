/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(7 * 60)
class GenerationPointerPublicationE2ETest {
  @ParameterizedTest
  @CsvSource({"first,before-backup", "first,after-backup", "first,after-publication",
      "replacement,before-backup", "replacement,after-backup", "replacement,after-publication",
      "recovery,before-backup", "recovery,after-backup", "recovery,after-publication"})
  void killedWorkerCanRestartAtEachPointerPublicationCut(String mode, String point) throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("pointer-" + mode + "-" + point);
  }
}
