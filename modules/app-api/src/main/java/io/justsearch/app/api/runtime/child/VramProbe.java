/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemorySource;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The adapter memory reading the GPU release poll compares (Spec B §2.6). Adapter totals only: per
 * process counters are never summed.
 */
public interface VramProbe {

  /** Where readings come from; {@link GpuMemorySource#NONE} when there is no probe. */
  GpuMemorySource source();

  /** Bytes in use on the adapter now; empty when it cannot be read. Never throws. */
  OptionalLong adapterUsedBytes(Optional<GpuDevice> device);

  /** No probe: every GPU release is UNMEASURED. */
  static VramProbe none() {
    return new VramProbe() {
      @Override
      public GpuMemorySource source() {
        return GpuMemorySource.NONE;
      }

      @Override
      public OptionalLong adapterUsedBytes(Optional<GpuDevice> device) {
        return OptionalLong.empty();
      }
    };
  }
}
