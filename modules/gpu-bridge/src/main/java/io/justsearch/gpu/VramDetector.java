/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import io.justsearch.app.api.runtime.child.OwnedHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects actual available VRAM from the GPU using nvidia-smi.
 *
 * <p>This class queries the GPU for actual VRAM availability via nvidia-smi.
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

  /** Timeout for nvidia-smi commands in seconds. */
  private static final int NVIDIA_SMI_TIMEOUT_SECONDS = 5;

  // Cached values to avoid repeated nvidia-smi calls
  private Long cachedTotalVram = null;
  private long cacheTimestamp = 0;
  private static final long CACHE_TTL_MS = 60_000; // 1 minute cache

  // Sticky failure flag: once nvidia-smi fails (not found / non-zero exit),
  // all subsequent calls short-circuit without spawning a process.
  private volatile boolean nvidiaSmiUnavailable = false;

  /**
   * Detects total VRAM in bytes using nvidia-smi.
   *
   * @return total VRAM in bytes, or -1 if detection fails
   */
  public long getTotalVramBytes() {
    if (nvidiaSmiUnavailable) {
      return -1;
    }
    // Check cache
    if (cachedTotalVram != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS) {
      return cachedTotalVram;
    }

    try {
      ProcessBuilder pb = new ProcessBuilder(
          "nvidia-smi",
          "--query-gpu=memory.total",
          "--format=csv,noheader,nounits");
      pb.redirectErrorStream(true);
      Process proc = OwnedHelper.start(pb, "nvidia-smi VRAM probe");

      // Wait BEFORE reading: the timeout must bound the call. nvidia-smi emits a single
      // short line, so its output fits the OS pipe buffer and it never blocks on write —
      // reading after the process has exited (or been killed) cannot deadlock, but reading
      // first would let readLine() block indefinitely on a hung child, defeating the timeout.
      boolean exited = proc.waitFor(NVIDIA_SMI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!exited) {
        OwnedHelper.terminate(proc, "timeout");
        LOG.warn("nvidia-smi timed out");
        return -1;
      }

      String output;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.readLine();
      }

      if (proc.exitValue() != 0 || output == null || output.isBlank()) {
        LOG.debug("nvidia-smi failed or returned empty output — disabling future polls");
        nvidiaSmiUnavailable = true;
        return -1;
      }

      long vramBytes = parseNvidiaSmiFirstGpuBytes(output);
      if (vramBytes < 0) {
        // Unparseable output is a transient glitch, not a missing tool — do not set the
        // sticky flag (matches the legacy NumberFormatException path).
        LOG.debug("Failed to parse nvidia-smi total-memory output: {}", output);
        return -1;
      }

      // Cache the result
      cachedTotalVram = vramBytes;
      cacheTimestamp = System.currentTimeMillis();

      LOG.debug("Detected total VRAM: {} bytes", vramBytes);
      return vramBytes;

    } catch (Exception e) {
      LOG.debug("nvidia-smi not available — disabling future polls: {}", e.getMessage());
      nvidiaSmiUnavailable = true;
      return -1;
    }
  }

  /**
   * Detects available (free) VRAM in bytes using nvidia-smi.
   *
   * @return available VRAM in bytes, or -1 if detection fails
   */
  public long getAvailableVramBytes() {
    if (nvidiaSmiUnavailable) {
      return -1;
    }
    try {
      ProcessBuilder pb = new ProcessBuilder(
          "nvidia-smi",
          "--query-gpu=memory.free",
          "--format=csv,noheader,nounits");
      pb.redirectErrorStream(true);
      Process proc = OwnedHelper.start(pb, "nvidia-smi VRAM probe");

      // Wait before reading — see getTotalVramBytes() for why the timeout must precede the read.
      boolean exited = proc.waitFor(NVIDIA_SMI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!exited) {
        OwnedHelper.terminate(proc, "timeout");
        LOG.warn("nvidia-smi timed out");
        return -1;
      }

      String output;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.readLine();
      }

      if (proc.exitValue() != 0 || output == null || output.isBlank()) {
        LOG.debug("nvidia-smi failed or returned empty output");
        return -1;
      }

      long vramBytes = parseNvidiaSmiFirstGpuBytes(output);
      if (vramBytes < 0) {
        LOG.debug("Failed to parse nvidia-smi free-memory output: {}", output);
        return -1;
      }

      LOG.debug("Detected available VRAM: {} bytes", vramBytes);
      return vramBytes;

    } catch (Exception e) {
      LOG.debug("nvidia-smi free memory detection failed: {}", e.getMessage());
      return -1;
    }
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
    if (nvidiaSmiUnavailable) {
      return null;
    }
    try {
      ProcessBuilder pb =
          new ProcessBuilder(
              "nvidia-smi",
              "--query-gpu=driver_version",
              "--format=csv,noheader");
      pb.redirectErrorStream(true);
      Process proc = OwnedHelper.start(pb, "nvidia-smi VRAM probe");

      // Wait before reading — see getTotalVramBytes() for why the timeout must precede the read.
      boolean exited = proc.waitFor(NVIDIA_SMI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!exited) {
        OwnedHelper.terminate(proc, "timeout");
        return null;
      }

      String output;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.readLine();
      }

      if (proc.exitValue() != 0 || output == null || output.isBlank()) {
        return null;
      }
      return output.trim().lines().findFirst().orElse("").trim();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Invalidates the cached VRAM value, forcing a fresh query on next call.
   */
  public void invalidateCache() {
    cachedTotalVram = null;
    cacheTimestamp = 0;
    nvidiaSmiUnavailable = false;
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
