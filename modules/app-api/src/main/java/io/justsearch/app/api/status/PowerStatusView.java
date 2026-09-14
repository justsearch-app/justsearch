/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OS energy-intent snapshot for the /api/status endpoint (tempdoc 630).
 *
 * <p>Populated from the {@code EnergyStatePoller.energyState()} poll. When {@code
 * energyReduced} is true the OS has asked apps to reduce background activity (e.g. Windows Energy
 * Saver) and the Worker is deferring GPU/CPU-heavy bulk backfill — the Health Queue card renders
 * this as the calm "Paused — saving energy" state. {@code source} ({@code AC}/{@code BATTERY}/{@code
 * UNKNOWN}) is wording-only. This is not a degradation: search keeps working.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PowerStatusView(boolean energyReduced, String source) {

  /** Default view when the energy probe is unavailable/unknown (⇒ not reduced). */
  public static PowerStatusView unknown() {
    return new PowerStatusView(false, "UNKNOWN");
  }
}
