/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.power;

import io.justsearch.configuration.SystemAccess;
import java.lang.foreign.ValueLayout;
import java.util.Locale;

/** The one per-process registration behind {@link SystemPowerEvents#installAtBoot}. */
final class BootPowerEvents {
  private static SystemPowerEvents installed;

  private BootPowerEvents() {}

  static synchronized SystemPowerEvents install() {
    if (installed == null) installed = decide();
    return installed;
  }

  static synchronized SystemPowerEvents current() {
    return installed != null ? installed : NoPowerEvents.notApplicable();
  }

  private static SystemPowerEvents decide() {
    String os = SystemAccess.sysProp("os.name", "").toLowerCase(Locale.ROOT);
    if (!os.startsWith("windows")) return NoPowerEvents.notApplicable();
    if (ValueLayout.ADDRESS.byteSize() != 8) return NoPowerEvents.unavailable("32-bit");
    WindowsPowerNotifications registered = WindowsPowerNotifications.register();
    return registered.registrationResult() == WindowsPowerNotifications.ERROR_SUCCESS
        ? registered : NoPowerEvents.unavailable(registered.failure());
  }
}
