/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RecordedInstallerCallbacksTest {
  @Test
  void forwardsPublicationCallbacksWithInstallerBoundariesInOrder() throws IOException {
    List<String> calls = new ArrayList<>();
    var callbacks = prepared(calls).callbacks();
    callbacks.includeComponentObservation(null);
    callbacks.admitBeforePointer();
    callbacks.afterPointerCommitted();
    callbacks.afterRuntimePublished();
    assertEquals(List.of("include", "INSTALLER_BEFORE_ARM", "admit",
        "INSTALLER_BEFORE_POINTER", "INSTALLER_POINTER_BEFORE_SETTINGS", "pointer",
        "INSTALLER_SETTINGS_BEFORE_PUBLICATION", "published", "INSTALLER_BEFORE_RECEIPT"),
        calls);
  }

  @Test
  void forwardsPhysicalOwnerLockAroundPromotion() throws IOException {
    List<String> calls = new ArrayList<>();
    prepared(calls).withOwnerLocks(() -> {
      calls.add("promote");
      return null;
    });
    assertEquals(List.of("owner-lock-begin", "promote", "owner-lock-end"), calls);
  }

  @Test
  void forwardsPrecommitAbortToOwner() {
    List<String> calls = new ArrayList<>();
    var prepared = prepared(calls);
    prepared.callbacks().abortBeforePointer();
    prepared.abortBeforePointer();
    assertEquals(List.of("abort", "refuse"), calls);
  }

  private static io.justsearch.indexerworker.server.RecordedIngestionLifecycle.PreparedCompositeProjection
      prepared(List<String> calls) {
    OperationAttemptRunner attempts = mock(OperationAttemptRunner.class);
    OperationRecordHandle handle = mock(OperationRecordHandle.class);
    doAnswer(invocation -> {
      calls.add(invocation.getArgument(1, OperationAttemptRunner.BulkBoundary.class).name());
      return null;
    }).when(attempts).observeBulkBoundary(eq(handle), any());
    SettingsCommitOwner.PreparedGenerationProjection projection = recording(calls);
    return RecordedIngestionCoordinator.preparedInstallerProjection(
        projection, attempts, handle, () -> calls.add("refuse"));
  }

  private static SettingsCommitOwner.PreparedGenerationProjection recording(List<String> calls) {
    return new SettingsCommitOwner.PreparedGenerationProjection() {
      @Override public void includeComponentObservation(EngineComponentSnapshot.Component observation) {
        calls.add("include");
      }
      @Override public void withOwnerLocks(Runnable publication) {
        calls.add("owner-lock-begin");
        try { publication.run(); }
        finally { calls.add("owner-lock-end"); }
      }
      @Override public void admitBeforePointer() { calls.add("admit"); }
      @Override public void afterPointerCommitted() { calls.add("pointer"); }
      @Override public void afterRuntimePublished() { calls.add("published"); }
      @Override public void abortBeforePointer() { calls.add("abort"); }
    };
  }
}
