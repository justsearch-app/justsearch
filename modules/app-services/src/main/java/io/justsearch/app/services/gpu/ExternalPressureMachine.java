/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The external-use pressure machine (Spec E section 3.3, step E3; reconciliation CH11). Pure: one
 * call per sampler tick, no clock or thread of its own.
 *
 * <pre>
 * pdhKnown &amp;&amp; free &lt; freeMargin &amp;&amp; external &gt; allowance + freeMargin   -&gt; raiseCount++, clearCount = 0
 * pdhKnown &amp;&amp; (free &gt;= freeMargin + 256 || external &lt;= allowance)       -&gt; clearCount++, raiseCount = 0
 * otherwise (UNKNOWN or NVML-only)                                      -&gt; counters frozen
 * NONE -&gt; EXTERNAL_MEMORY   when raiseCount &gt;= 3
 * EXTERNAL_MEMORY -&gt; NONE   when clearCount &gt;= 5, or no PDH-known sample for 30 s
 * </pre>
 *
 * <p>Only external use counts: the adapter's used memory minus our own processes' dedicated use, read
 * per process from PDH. Our own processes cannot raise it (CH11). A PDH-known sample that meets
 * neither condition breaks both runs, because each count is of <em>consecutive</em> samples.
 */
public final class ExternalPressureMachine {

  private static final long MIB = 1024L * 1024L;

  /** {@code freeMarginMiB} 512 (Spec E section 0). */
  public static final long FREE_MARGIN_BYTES = 512L * MIB;

  /** {@code plannedExternalAllowanceMiB} 1,024, provisional until M7 (Spec E section 0). */
  public static final long PLANNED_EXTERNAL_ALLOWANCE_BYTES = 1_024L * MIB;

  /** The clear needs this much free memory above the margin (hysteresis). */
  public static final long CLEAR_HEADROOM_BYTES = 256L * MIB;

  /** Consecutive PDH-known samples that raise pressure (6 s at the 2 s period). */
  public static final int RAISE_SAMPLES = 3;

  /** Consecutive PDH-known samples that clear it (10 s). */
  public static final int CLEAR_SAMPLES = 5;

  /** {@code unknownClearMs}: pressure clears after this long without a PDH-known sample. */
  public static final Duration UNKNOWN_CLEAR = Duration.ofSeconds(30);

  private final long allowanceBytes;
  private boolean pressure;
  private int raiseCount;
  private int clearCount;
  private Instant lastKnownAt;

  /** The planned external allowance (1,024 MiB). */
  public ExternalPressureMachine() {
    this(PLANNED_EXTERNAL_ALLOWANCE_BYTES);
  }

  /** @param allowanceBytes the profile's planned external allowance */
  public ExternalPressureMachine(long allowanceBytes) {
    if (allowanceBytes < 0) throw new IllegalArgumentException("allowanceBytes must be >= 0");
    this.allowanceBytes = allowanceBytes;
  }

  /**
   * Advances on a PDH-known sample: per-process figures were present, so external use is known.
   *
   * @param freeBytes the adapter's free memory
   * @param externalBytes adapter used minus our processes' dedicated use
   * @return whether external pressure is raised after this sample
   */
  public boolean advanceKnown(long freeBytes, long externalBytes, Instant at) {
    Objects.requireNonNull(at, "at");
    lastKnownAt = at;
    if (freeBytes < FREE_MARGIN_BYTES && externalBytes > allowanceBytes + FREE_MARGIN_BYTES) {
      raiseCount++;
      clearCount = 0;
    } else if (freeBytes >= FREE_MARGIN_BYTES + CLEAR_HEADROOM_BYTES
        || externalBytes <= allowanceBytes) {
      clearCount++;
      raiseCount = 0;
    } else {
      raiseCount = 0;
      clearCount = 0;
    }
    if (!pressure && raiseCount >= RAISE_SAMPLES) {
      pressure = true;
      raiseCount = 0;
    } else if (pressure && clearCount >= CLEAR_SAMPLES) {
      pressure = false;
      clearCount = 0;
    }
    return pressure;
  }

  /**
   * Advances on an UNKNOWN or NVML-only sample: the counters are frozen. Raised pressure clears once
   * no PDH-known sample has arrived for {@link #UNKNOWN_CLEAR}, so UNKNOWN never holds bulk work
   * for long.
   *
   * @return whether external pressure is raised after this sample
   */
  public boolean advanceUnknown(Instant at) {
    Objects.requireNonNull(at, "at");
    if (pressure
        && (lastKnownAt == null || !at.isBefore(lastKnownAt.plus(UNKNOWN_CLEAR)))) {
      pressure = false;
      raiseCount = 0;
      clearCount = 0;
    }
    return pressure;
  }

  /** Whether external pressure is raised. */
  public boolean pressure() {
    return pressure;
  }
}
