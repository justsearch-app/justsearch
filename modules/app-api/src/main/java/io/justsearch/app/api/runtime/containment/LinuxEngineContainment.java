/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import io.justsearch.app.api.runtime.child.BoundChildProcess;
import java.util.Optional;

/** Direct children launched through the bound-child seam, not inherited Windows job membership. */
final class LinuxEngineContainment implements EngineContainment {
  @Override public Status status() { return Status.CONTAINED; }
  @Override public Optional<String> unavailableReason() { return Optional.empty(); }
  @Override public Optional<Boolean> contains(ProcessHandle child) { return BoundChildProcess.contains(child); }
  @Override public String toString() { return "LinuxEngineContainment[PR_SET_PDEATHSIG]"; }
}
