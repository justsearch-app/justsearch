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
    return decision(footprintBytes, null);
  }

  /**
   * Retire the serving set only when that can make the candidate fit (D1-14, 2026-09-27 owner
   * decision). {@code releasableSourceBytes} is the serving set's own device footprint; {@code null}
   * means it is unknown and keeps the unconditional in-place fallback.
   */
  public ComposeEvidence decision(long footprintBytes, Long releasableSourceBytes) {
    if (footprintBytes < 0) throw new IllegalArgumentException("footprint must be non-negative");
    if (releasableSourceBytes != null && releasableSourceBytes < 0) {
      throw new IllegalArgumentException("releasable source bytes must be non-negative");
    }
    if (footprintBytes == 0 || freeBytes != null && footprintBytes <= freeBytes) {
      return evidence(ComposeEvidence.Mode.BESIDE, "candidate_fits_free_device_memory", footprintBytes);
    }
    if (releasableSourceBytes == null) {
      return evidence(ComposeEvidence.Mode.IN_PLACE, freeBytes == null
          ? "free_device_memory_unknown" : "candidate_exceeds_free_device_memory", footprintBytes);
    }
    if (releasableSourceBytes == 0) {
      // Retiring a source that holds no device memory frees nothing: never pause it for B.
      return freeBytes == null
          ? evidence(ComposeEvidence.Mode.BESIDE, "source_holds_no_device_memory", footprintBytes)
          : evidence(ComposeEvidence.Mode.REFUSED, "candidate_exceeds_releasable_device_memory",
              footprintBytes);
    }
    if (freeBytes == null) {
      return evidence(ComposeEvidence.Mode.IN_PLACE, "free_device_memory_unknown", footprintBytes);
    }
    return footprintBytes - freeBytes <= releasableSourceBytes
        ? evidence(ComposeEvidence.Mode.IN_PLACE, "candidate_fits_after_source_release",
            footprintBytes)
        : evidence(ComposeEvidence.Mode.REFUSED, "candidate_exceeds_releasable_device_memory",
            footprintBytes);
  }

  private ComposeEvidence evidence(ComposeEvidence.Mode mode, String reason, long footprintBytes) {
    return new ComposeEvidence(mode, reason, freeBytes, footprintBytes);
  }

  private static Long clamp(Long value, long ceilingBytes) {
    return value == null ? null : Math.min(value, ceilingBytes);
  }
}
