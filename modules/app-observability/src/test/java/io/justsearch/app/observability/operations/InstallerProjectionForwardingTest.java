/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class InstallerProjectionForwardingTest {
  @Test
  void forwardsAllCallbacksAndClosesCancellationAfterPointer() throws IOException {
    List<String> calls = new ArrayList<>();
    var projection = OperationAttemptRunnerImpl.installerProjectionWithCancellation(
        recording(calls), () -> calls.add("cancel.close"));
    projection.includeComponentObservation(null);
    projection.withOwnerLocks(() -> calls.add("publication"));
    projection.admitBeforePointer();
    projection.afterPointerCommitted();
    projection.afterRuntimePublished();
    assertEquals(List.of("include", "lock.enter", "publication", "lock.exit", "admit",
        "pointer", "cancel.close", "published"), calls);
  }

  @Test
  void forwardsAbortAndClosesCancellation() {
    List<String> calls = new ArrayList<>();
    var projection = OperationAttemptRunnerImpl.installerProjectionWithCancellation(
        recording(calls), () -> calls.add("cancel.close"));
    projection.abortBeforePointer();
    assertEquals(List.of("abort", "cancel.close"), calls);
  }

  private static SettingsCommitOwner.PreparedGenerationProjection recording(List<String> calls) {
    return new SettingsCommitOwner.PreparedGenerationProjection() {
      @Override public void includeComponentObservation(EngineComponentSnapshot.Component observation) {
        calls.add("include");
      }
      @Override public void withOwnerLocks(Runnable publication) {
        calls.add("lock.enter");
        publication.run();
        calls.add("lock.exit");
      }
      @Override public void admitBeforePointer() { calls.add("admit"); }
      @Override public void afterPointerCommitted() { calls.add("pointer"); }
      @Override public void afterRuntimePublished() { calls.add("published"); }
      @Override public void abortBeforePointer() { calls.add("abort"); }
    };
  }
}
