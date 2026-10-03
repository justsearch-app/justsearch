/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import java.util.List;

/** Point-in-time executor ownership projection. */
public record EngineExecutorSnapshot(List<Registration> registrations, int timerRegistrations) {
  public EngineExecutorSnapshot {
    registrations = List.copyOf(registrations);
  }

  public record Registration(
      EngineExecutorSpec spec, boolean closed, int liveInstances, int shutdownInstances,
      int queuedTasks) {}
}
