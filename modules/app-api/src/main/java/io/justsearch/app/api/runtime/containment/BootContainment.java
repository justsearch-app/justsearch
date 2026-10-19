/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import java.lang.foreign.ValueLayout;

/** The one per-process containment decision behind {@link EngineContainment#installAtBoot}. */
final class BootContainment {
  private static EngineContainment installed;

  private BootContainment() {}

  static synchronized EngineContainment install() {
    if (installed == null) installed = decide();
    return installed;
  }

  static synchronized EngineContainment current() {
    return installed != null ? installed : NoEngineContainment.unavailable("not_installed");
  }

  private static EngineContainment decide() {
    if (io.justsearch.configuration.PlatformPaths.isLinux()) {
      try {
        io.justsearch.app.api.runtime.child.BoundChildProcess.initialize();
        return new LinuxEngineContainment();
      } catch (java.io.IOException | RuntimeException unavailable) {
        return NoEngineContainment.unavailable("linux-bound-exec: " + unavailable.getMessage());
      }
    }
    if (!io.justsearch.configuration.PlatformPaths.isWindows()) return NoEngineContainment.notApplicable();
    if (ValueLayout.ADDRESS.byteSize() != 8) return NoEngineContainment.unavailable("32-bit");
    return WindowsEngineJob.install();
  }
}
