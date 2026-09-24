/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

/** Free and total device memory sampled at a component composition decision. */
public record DeviceMemoryLine(Long totalBytes, Long freeBytes) {
  public DeviceMemoryLine {
    if (totalBytes != null && totalBytes < 0 || freeBytes != null && freeBytes < 0) {
      throw new IllegalArgumentException("device memory bytes must be non-negative");
    }
  }

  /** The configured test ceiling limits both axes without changing the physical probe. */
  public DeviceMemoryLine withCeilingMb(Long ceilingMb) {
    if (ceilingMb == null) return this;
    if (ceilingMb < 0) {
      throw new IllegalArgumentException("device memory ceiling must be a non-negative MB value");
    }
    long ceilingBytes = ceilingMb > Long.MAX_VALUE / (1024L * 1024L)
        ? Long.MAX_VALUE : ceilingMb * 1024L * 1024L;
    return new DeviceMemoryLine(clamp(totalBytes, ceilingBytes), clamp(freeBytes, ceilingBytes));
  }

  /** Unknown free memory cannot certify that a second encoder set fits. */
  public ComposeEvidence decision(long footprintBytes) {
    if (footprintBytes < 0) throw new IllegalArgumentException("footprint must be non-negative");
    boolean fits = footprintBytes == 0 || freeBytes != null && footprintBytes <= freeBytes;
    return new ComposeEvidence(fits ? ComposeEvidence.Mode.BESIDE : ComposeEvidence.Mode.IN_PLACE,
        fits ? "candidate_fits_free_device_memory"
            : freeBytes == null ? "free_device_memory_unknown" : "candidate_exceeds_free_device_memory",
        freeBytes, footprintBytes);
  }

  private static Long clamp(Long value, long ceilingBytes) {
    return value == null ? null : Math.min(value, ceilingBytes);
  }
}
