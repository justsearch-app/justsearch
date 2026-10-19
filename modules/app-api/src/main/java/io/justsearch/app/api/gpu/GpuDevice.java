/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpu;

import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * One graphics adapter, identified by its Windows LUID in the PDH instance form ({@code
 * luid_0x<high>_0x<low>}, eight lower-case hex digits each), plus the CUDA device ordinal when it
 * is known.
 */
public record GpuDevice(String luid, OptionalInt cudaDevice) {
  private static final Pattern LUID = Pattern.compile("luid_0x[0-9a-f]{8}_0x[0-9a-f]{8}");

  public GpuDevice {
    Objects.requireNonNull(luid, "luid");
    Objects.requireNonNull(cudaDevice, "cudaDevice");
    luid = luid.toLowerCase(Locale.ROOT);
    if (!LUID.matcher(luid).matches()) {
      throw new IllegalArgumentException("luid must look like luid_0x<8 hex>_0x<8 hex>: " + luid);
    }
    if (cudaDevice.isPresent() && cudaDevice.getAsInt() < 0) {
      throw new IllegalArgumentException("cudaDevice must be non-negative");
    }
  }

  /** Formats the two LUID halves the way PDH names its counter instances. */
  public static String luidOf(int highPart, int lowPart) {
    return String.format(Locale.ROOT, "luid_0x%08x_0x%08x", highPart, lowPart);
  }
}
