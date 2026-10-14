/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects actual available VRAM from the GPU using nvidia-smi.
 *
 * <p>This class reads the GPU's VRAM through {@link NvidiaSmiReadings}: one shared nvidia-smi
 * reading per process, at most one probe at a time and at most once per five seconds.
 *
 * <h2>Usage</h2>
 * <pre>
 * VramDetector detector = new VramDetector();
 * if (detector.meetsVduRequirements()) {
 *     String[] flags = detector.getRecommendedLlamaServerFlags();
 *     // Start llama-server with flags
 * }
 * </pre>
 */
public final class VramDetector {
  private static final Logger LOG = LoggerFactory.getLogger(VramDetector.class);

  /**
   * Minimum VRAM required for VDU features (~7.5 GB). Canonical constant lives in {@link
   * io.justsearch.configuration.model.HardwareProfile#MINIMUM_VRAM_FOR_GGUF}.
   */
  private static final long MINIMUM_VRAM_FOR_VDU =
      io.justsearch.configuration.model.HardwareProfile.MINIMUM_VRAM_FOR_GGUF;

  /**
   * Comfortable VRAM threshold where KV cache quantization is optional (~12 GB). Authority:
   * {@link VramRequirements} {@code COMFORTABLE_VRAM_BYTES}.
   */
  private static final long COMFORTABLE_VRAM = VramRequirements.COMFORTABLE_VRAM_BYTES;

  /** The process-wide nvidia-smi reading every detector shares (task b6d536 b101). */
  private final NvidiaSmiReadings readings;

  public VramDetector() {
    this(NvidiaSmiReadings.shared());
  }

  VramDetector(NvidiaSmiReadings readings) {
    this.readings = readings;
  }

  /** The shared reading, probing at most once per {@link NvidiaSmiReadings#MIN_INTERVAL}. */
  NvidiaSmiReadings.Reading reading() {
    return readings.read();
  }

  /**
   * Detects total VRAM in bytes using nvidia-smi.
   *
   * @return total VRAM in bytes, or -1 if detection fails
   */
  public long getTotalVramBytes() {
    NvidiaSmiReadings.Reading reading = reading();
    return reading == null || reading.totalBytes() == null ? -1 : reading.totalBytes();
  }

  /**
   * Detects available (free) VRAM in bytes using nvidia-smi.
   *
   * @return available VRAM in bytes, or -1 if detection fails
   */
  public long getAvailableVramBytes() {
    NvidiaSmiReadings.Reading reading = reading();
    return reading == null || reading.freeBytes() == null ? -1 : reading.freeBytes();
  }

  /**
   * Returns true if the GPU has enough total VRAM for VDU features (8GB minimum).
   *
   * <p>Note: 8GB is the minimum, but may require KV cache quantization flags.
   */
  public boolean meetsVduRequirements() {
    long vram = getTotalVramBytes();
    if (vram < 0) {
      LOG.warn("Cannot detect VRAM; assuming VDU requirements not met");
      return false;
    }
    boolean meets = vram >= MINIMUM_VRAM_FOR_VDU;
    if (!meets) {
      LOG.info("VDU requirements not met: {} GB < {} GB required",
          formatGb(vram), formatGb(MINIMUM_VRAM_FOR_VDU));
    }
    return meets;
  }

  /**
   * Returns true if GPU has comfortable headroom (12GB+).
   *
   * <p>12GB+ cards can skip KV cache quantization flags for better quality.
   */
  public boolean hasComfortableVram() {
    long vram = getTotalVramBytes();
    if (vram < 0) {
      return false;
    }
    return vram >= COMFORTABLE_VRAM;
  }

  /**
   * Returns recommended llama-server launch flags based on detected VRAM.
   *
   * <p>For 8GB cards, includes KV cache quantization flags (-ctk q4_0 -ctv q4_0).
   * For 12GB+ cards, skips quantization for better quality.
   *
   * <p>All supported cards get {@code -fa} (flash-attention) — raw llama-bench on an
   * RTX 4070 (12 GB) with Meta-Llama-3.1-8B-Q4_K_M measured +11.5 % prompt-processing
   * throughput (4199 -> 4684 tok/s) and +3.1 % token-generation (86.5 -> 89.2 tok/s)
   * vs {@code -fa 0}. See tempdoc 390 Batch G probe (2026-04-20).
   *
   * @return array of flags, or null if VRAM is insufficient
   */
  public String[] getRecommendedLlamaServerFlags() {
    if (!meetsVduRequirements()) {
      return null;
    }

    if (hasComfortableVram()) {
      // 12GB+ can skip KV quantization
      LOG.info("Comfortable VRAM detected ({}); KV cache quantization not required",
          getVramDescription());
      // Tempdoc 374 alpha.27 post-ship: -fa requires explicit value in llama-server b8571.
      // See VramRequirements.recommendedLlamaServerFlags javadoc. This legacy method is
      // dead post-alpha.27 (ArchUnit guards external use; no internal callers); kept in
      // sync with VramRequirements for any forensic reading.
      return new String[]{"-c", "4096", "-ngl", "99", "-fa", "on"};
    } else {
      // 8GB MUST use KV quantization to avoid OOM
      LOG.info("Limited VRAM detected ({}); enabling KV cache quantization",
          getVramDescription());
      return new String[]{"-c", "4096", "-ngl", "99", "-fa", "on", "-ctk", "q4_0", "-ctv", "q4_0"};
    }
  }

  /**
   * Returns human-readable VRAM description for logging/UI.
   *
   * @return description like "12.0 GB" or "Unknown (nvidia-smi not available)"
   */
  public String getVramDescription() {
    long vram = getTotalVramBytes();
    if (vram < 0) {
      return "Unknown (nvidia-smi not available)";
    }
    return String.format("%.1f GB", vram / (1024.0 * 1024.0 * 1024.0));
  }

  /**
   * Checks if CUDA GPU is available at all.
   *
   * @return true if nvidia-smi succeeds
   */
  public boolean isCudaAvailable() {
    return getTotalVramBytes() > 0;
  }

  /**
   * Best-effort: returns the NVIDIA driver version as reported by {@code nvidia-smi}.
   *
   * @return driver version like {@code "591.59"}, or null if unavailable
   */
  public String getDriverVersion() {
    NvidiaSmiReadings.Reading reading = reading();
    return reading == null ? null : reading.driverVersion();
  }

  /**
   * Makes the next reading a fresh probe whatever its age, and retries a sticky failure. Shared
   * by all readers: concurrent forced reads still start one process.
   */
  public void invalidateCache() {
    readings.invalidate();
  }

  private static String formatGb(long bytes) {
    return String.format("%.1f", bytes / (1024.0 * 1024.0 * 1024.0));
  }

  /**
   * Parses an {@code nvidia-smi --query-gpu=memory.{total,free} --format=csv,noheader,nounits}
   * output block into bytes for the first GPU.
   *
   * <p>nvidia-smi reports one line of MB per GPU; multi-GPU hosts emit several lines and we take
   * the first. The value is converted MB&rarr;bytes. Returns {@code -1} for any input that does
   * not yield a non-negative integer first line (null, blank, {@code "[N/A]"},
   * {@code "Insufficient Permissions"}, etc.) so the caller can treat it as a transient parse
   * failure without spawning logic leaking in here. Package-visible for unit testing — the
   * surrounding process plumbing is not directly testable, but this parse is the error-prone part.
   */
  static long parseNvidiaSmiFirstGpuBytes(String output) {
    if (output == null) {
      return -1;
    }
    String firstGpu = output.trim().lines().findFirst().orElse("").trim();
    if (firstGpu.isEmpty()) {
      return -1;
    }
    try {
      long vramMb = Long.parseLong(firstGpu);
      if (vramMb < 0) {
        return -1;
      }
      return vramMb * 1024 * 1024;
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
