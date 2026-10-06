/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import io.justsearch.configuration.SystemAccess;
import java.lang.foreign.ValueLayout;
import java.util.Locale;

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
    String os = SystemAccess.sysProp("os.name", "").toLowerCase(Locale.ROOT);
    if (!os.startsWith("windows")) return NoEngineContainment.notApplicable();
    if (ValueLayout.ADDRESS.byteSize() != 8) return NoEngineContainment.unavailable("32-bit");
    return WindowsEngineJob.install();
  }
}
