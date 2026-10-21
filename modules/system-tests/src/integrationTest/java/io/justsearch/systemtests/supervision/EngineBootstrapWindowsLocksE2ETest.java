/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(7 * 60)
class EngineBootstrapWindowsLocksE2ETest {
  @Test
  void initialBootstrapSurvivesHostileLocks() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario("lock-boot");
  }
}
