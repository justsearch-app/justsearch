/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tempdoc 629 (FLOOR) — at-rest protection snapshot for the {@code /api/status} endpoint.
 *
 * <p>Carries the coarse OS-disk-encryption state of the volume that hosts the data dir (the data
 * that backs the index, job queue, and conversations), plus its provenance + confidence — mirroring
 * the GPU capability's confidence-carrying projection ({@link GpuStatusView}, tempdoc 587).
 *
 * <p>{@code qualityKnown} is {@code false} whenever the configuration-quality distinction (weak
 * TPM-only vs a secure pre-boot PIN) and cloud-escrow status could not be read — which, for a
 * non-elevated Head, is always (629 confidence-probe P1). The frontend renders configuration
 * quality as "unknown — needs admin" in that case, so the surface never over-claims.
 *
 * <p>{@code probedAtMs} (task b6d536 b101) is when the probe that produced this value read the
 * volume, in epoch milliseconds. The state is read at most once per volume per ten minutes, and a
 * value kept after a failed probe keeps the time of the read that gave it, so a reader can tell how
 * old the value is.
 *
 * <p>Fields are nullable; {@code @JsonInclude(NON_NULL)} drops absent fields from the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AtRestProtectionView(
    String diskEncryption, String source, String confidence, boolean qualityKnown, Long probedAtMs) {

  /** A view without a read time. */
  public AtRestProtectionView(String diskEncryption, String source, String confidence, boolean qualityKnown) {
    this(diskEncryption, source, confidence, qualityKnown, null);
  }

  /** A view indicating the at-rest state could not be determined. */
  public static AtRestProtectionView unknown() {
    return new AtRestProtectionView("UNKNOWN", "none", "UNKNOWN", false);
  }
}
