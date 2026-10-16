/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

/**
 * v3: GPU capability snapshot (NVML-first, with nvidia-smi fallback).
 *
 * <p>This is intentionally Windows/NVIDIA oriented. Non-Windows callers should expect
 * {@code effective.cudaAvailable=false} and {@code effective.confidence=UNKNOWN}.
 */
public record GpuCapabilities(
    Nvml nvml,
    NvidiaSmi nvidiaSmi,
    Effective effective
) {
  public enum Confidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
  }

  public record Nvml(
      boolean available,
      String attemptedPath,
      String loadedPath,
      String error,
      String driverVersion,
      Integer driverVersionMajor,
      Integer driverVersionMinor,
      Integer deviceCount,
      Long totalVramBytes,
      Long freeVramBytes,
      Long usedVramBytes,
      Integer gpuUtilizationPercent,
      Integer memoryUtilizationPercent
  ) {}

  /**
   * The driver and memory figures nvidia-smi reports. Where NVML answered in-process (task b6d536
   * b101) no nvidia-smi process is started and the same figures come from NVML: {@code source}
   * names where they were read ({@link #SOURCE_NVIDIA_SMI} or {@link #SOURCE_NVML}), and
   * {@code unavailable} names each figure that source did not give, with why.
   */
  public record NvidiaSmi(
      boolean available,
      String error,
      String driverVersion,
      Integer driverVersionMajor,
      Integer driverVersionMinor,
      Long totalVramBytes,
      Long freeVramBytes,
      String vramDescription,
      String source,
      java.util.Map<String, String> unavailable
  ) {
    /** The figures were read from an nvidia-smi process. */
    public static final String SOURCE_NVIDIA_SMI = "nvidia-smi";
    /** The figures were read in-process from NVML; no nvidia-smi process was started. */
    public static final String SOURCE_NVML = "read in-process (NVML)";

    public NvidiaSmi {
      unavailable = unavailable == null ? java.util.Map.of()
          : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(unavailable));
    }

    /** Figures read from nvidia-smi. */
    public NvidiaSmi(boolean available, String error, String driverVersion, Integer driverVersionMajor,
        Integer driverVersionMinor, Long totalVramBytes, Long freeVramBytes, String vramDescription) {
      this(available, error, driverVersion, driverVersionMajor, driverVersionMinor, totalVramBytes,
          freeVramBytes, vramDescription, SOURCE_NVIDIA_SMI, java.util.Map.of());
    }
  }

  /**
   * The CUDA-functional axis: "is the CUDA runtime actually loadable" (the nvcuda driver-API
   * probe), as distinct from {@link Effective#cudaAvailable} which means "a CUDA-capable GPU is
   * physically present" (derived from the VRAM/device probes). This is a SINGLE-probe axis — it
   * does not participate in the NVML-vs-nvidia-smi precedence merge; it rides the same effective
   * bundle so policy reads one resolver for every GPU fact (tempdoc 587). The probe lives in
   * {@code ort-common} ({@code GpuDriverApiProbe}); gpu-bridge cannot reach it, so the value is
   * supplied as an input to the merge by the one composition seam that depends on both modules.
   *
   * @param functional {@code TRUE}/{@code FALSE} when probed, or {@code null} when not probed
   * @param source provenance, e.g. {@code "cuda-driver-api"} or {@code "none"}
   * @param confidence {@code HIGH} when the probe ran, {@code UNKNOWN} when it did not
   */
  public record Cuda(Boolean functional, String source, Confidence confidence) {
    /** The not-probed sentinel: functional unknown, no source, UNKNOWN confidence. */
    public static Cuda unknown() {
      return new Cuda(null, "none", Confidence.UNKNOWN);
    }
  }

  public record Effective(
      boolean cudaAvailable,
      String source,
      Confidence confidence,
      String driverVersion,
      Integer driverVersionMajor,
      Integer driverVersionMinor,
      Integer deviceCount,
      Long totalVramBytes,
      Long freeVramBytes,
      Long usedVramBytes,
      Cuda cuda
  ) {}
}
