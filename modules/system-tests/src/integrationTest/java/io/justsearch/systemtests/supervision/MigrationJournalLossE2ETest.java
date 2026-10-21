/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(7 * 60)
class MigrationJournalLossE2ETest {
  @ParameterizedTest
  @ValueSource(strings = {"journal-loss-corrupt", "journal-loss-missing", "journal-loss-empty", "journal-loss-stale"})
  void deletedContentCannotBeServedBeforeReadinessOrAfterLostJournalRestart(String scenario) throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario(scenario);
  }
}
