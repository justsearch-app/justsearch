/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.IndexingService.MigrationOutcome;
import io.justsearch.ipc.MigrationCutoverResponse;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationStartResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pins the requirement across the real client mapping, independently of acceptance. */
final class MigrationOutcomeProjectionTest {
  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,false"})
  void allMigrationResponsesPreserveTheRequirement(boolean accepted, boolean restartRequired) {
    IngestServiceCalls calls = mock(IngestServiceCalls.class);
    when(calls.startMigration(any())).thenReturn(MigrationStartResponse.newBuilder()
        .setAccepted(accepted).setRestartRequired(restartRequired).build());
    when(calls.requestCutover(any())).thenReturn(MigrationCutoverResponse.newBuilder()
        .setAccepted(accepted).setRestartRequired(restartRequired).build());
    when(calls.rollbackMigration(any())).thenReturn(MigrationRollbackResponse.newBuilder()
        .setAccepted(accepted).setRestartRequired(restartRequired).build());
    try (var client = new TestKnowledgeClient(null, calls, null)) {
      var expected = new MigrationOutcome(accepted, restartRequired);
      assertEquals(expected, client.startMigration("manual", io.justsearch.app.services.TestEngineContexts.durableInternal()));
      assertEquals(expected, client.requestCutover(true, io.justsearch.app.services.TestEngineContexts.durableInternal()));
      assertEquals(expected, client.rollbackMigration(io.justsearch.app.services.TestEngineContexts.durableInternal()));
    }
  }
}
