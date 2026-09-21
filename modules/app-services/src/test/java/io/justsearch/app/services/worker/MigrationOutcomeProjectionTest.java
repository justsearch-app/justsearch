/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.IndexingService.MigrationOutcome;
import io.justsearch.ipc.MigrationCutoverResponse;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pins the requirement across the real client mapping, independently of acceptance. */
final class MigrationOutcomeProjectionTest {
  private static final String OPERATION_KEY = "01994180-0000-7000-8000-000000000121";
  private static final String TARGET_FINGERPRINT = "a".repeat(64);

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
    try (var client =
        new TestKnowledgeClient(
            new io.justsearch.core.execution.TestEngineExecutors(), null, calls, null)) {
      var expected = new MigrationOutcome(accepted, restartRequired);
      assertEquals(expected, client.startMigration("manual", io.justsearch.app.services.TestEngineContexts.durableInternal()));
      assertEquals(expected, client.requestCutover(true, io.justsearch.app.services.TestEngineContexts.durableInternal()));
      assertEquals(expected, client.rollbackMigration(io.justsearch.app.services.TestEngineContexts.durableInternal()));
    }
  }

  @Test
  void recordedStartSendsExactIdentityAndProjectsGenerationWitness() {
    IngestServiceCalls calls = mock(IngestServiceCalls.class);
    when(calls.startMigration(any())).thenReturn(MigrationStartResponse.newBuilder()
        .setAccepted(true)
        .setRestartRequired(true)
        .setActiveGenerationId("g-active")
        .setBuildingGenerationId("g-" + OPERATION_KEY)
        .setMigrationState("MIGRATING")
        .build());
    try (var client = new TestKnowledgeClient(
        new io.justsearch.core.execution.TestEngineExecutors(), null, calls, null)) {
      MigrationOutcome actual = client.startRecordedMigration(
          OPERATION_KEY, "bulk_reindex", TARGET_FINGERPRINT,
          io.justsearch.app.services.TestEngineContexts.durableInternal());

      assertEquals(new MigrationOutcome(
          true, true, "g-active", "g-" + OPERATION_KEY, "MIGRATING"), actual);
      ArgumentCaptor<MigrationStartRequest> request =
          ArgumentCaptor.forClass(MigrationStartRequest.class);
      verify(calls).startMigration(request.capture());
      assertEquals(OPERATION_KEY, request.getValue().getRecordedOperationKey());
      assertEquals(TARGET_FINGERPRINT, request.getValue().getTargetIndexFingerprint());
      assertEquals("bulk_reindex", request.getValue().getReason());
      assertTrue(request.getValue().getRestartWorker());
    }
  }

  @Test
  void partialRecordedIdentityIsRefusedWithoutFallingBackToLegacyStart() {
    IngestServiceCalls calls = mock(IngestServiceCalls.class);
    try (var client = new TestKnowledgeClient(
        new io.justsearch.core.execution.TestEngineExecutors(), null, calls, null)) {
      var context = io.justsearch.app.services.TestEngineContexts.durableInternal();
      assertFalse(
          client.startRecordedMigration(OPERATION_KEY, "bulk_reindex", "", context).accepted());
      assertFalse(
          client.startRecordedMigration("", "bulk_reindex", TARGET_FINGERPRINT, context).accepted());
      assertFalse(
          client.startRecordedMigration(null, "bulk_reindex", TARGET_FINGERPRINT, context).accepted());
      verify(calls, never()).startMigration(any());
    }
  }
}
