/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import io.justsearch.agent.api.registry.Nullable;
import io.justsearch.agent.api.registry.PreciseWire;
import io.justsearch.app.api.gpu.GpuMemorySource;
import java.util.Objects;

/**
 * The {@code operating.gpu} block. With {@code source} NONE every reading is {@code null}: the
 * memory state is unknown, which counts as neither pressure nor fine. {@code spillSuspected} is an
 * alarm only and never a control input.
 *
 * <p>{@code externalUsedBytes} is the adapter's used memory minus our own processes' dedicated use
 * (CH11): the external-use reading. Like the per-process values it comes only from PDH. It is
 * diagnostic for now; no control decision reads it.
 */
public record OperatingGpuView(
    Profile profile,
    @Nullable ProfileSource profileSource,
    @Nullable String adapter,
    @Nullable Long adapterTotalBytes,
    @Nullable Long adapterFreeBytes,
    @Nullable Long ourDedicatedBytes,
    @Nullable Long ourSharedBytes,
    @Nullable Long externalUsedBytes,
    @Nullable Boolean spillSuspected,
    GpuMemorySource source,
    @Nullable String sampledAt,
    @Nullable Long ageMs,
    boolean stale)
    implements PreciseWire {

  public OperatingGpuView {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(source, "source");
    if ((profile == Profile.UNRESOLVED) != (profileSource == null)) {
      throw new IllegalArgumentException("profileSource is present exactly when a profile resolved");
    }
    if (source == GpuMemorySource.NONE
        && (adapterTotalBytes != null || adapterFreeBytes != null || ourDedicatedBytes != null
            || ourSharedBytes != null || externalUsedBytes != null || sampledAt != null || ageMs != null)) {
      throw new IllegalArgumentException("a NONE source carries no reading");
    }
    if (source != GpuMemorySource.PDH
        && (ourDedicatedBytes != null || ourSharedBytes != null || externalUsedBytes != null)) {
      throw new IllegalArgumentException("per-process memory is read only from PDH");
    }
  }

  /** The block when nothing is known: no profile and no reading. */
  public static OperatingGpuView unknown() {
    return new OperatingGpuView(
        Profile.UNRESOLVED, null, null, null, null, null, null, null, null, GpuMemorySource.NONE,
        null, null, false);
  }
}
