/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import java.util.Objects;
import java.util.Optional;

/**
 * One CUDA device by this process's ordinal (Spec E section 5.3). Identity fields are empty when the
 * driver did not give them (no LUID on Linux or for a TCC device).
 *
 * @param luid the LUID in PDH instance form, {@code luid_0x<high>_0x<low>}
 * @param uuid the UUID in nvidia-smi form, {@code GPU-…}, which {@code CUDA_VISIBLE_DEVICES} accepts
 * @param pciBusId the PCI bus id, {@code domain:bus:device.function}, which NVML looks devices up by
 * @param totalBytes total device memory, or 0 when unknown
 */
public record CudaDevice(
    int ordinal,
    Optional<String> luid,
    Optional<String> uuid,
    Optional<String> pciBusId,
    long totalBytes) {
  public CudaDevice {
    if (ordinal < 0) throw new IllegalArgumentException("ordinal must be non-negative");
    Objects.requireNonNull(luid, "luid");
    Objects.requireNonNull(uuid, "uuid");
    Objects.requireNonNull(pciBusId, "pciBusId");
  }
}
