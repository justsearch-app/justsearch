/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

/** v3: Produces a merged GPU capability snapshot (NVML-first, with a shared nvidia-smi fallback). */
public final class GpuCapabilitiesService {
  /** Why a figure is absent from a section NVML supplied. */
  static final String NOT_FROM_NVML = "not reported by NVML";

  private final NvmlService nvml;
  private final VramDetector vramDetector;

  public GpuCapabilitiesService() {
    this(new NvmlService(), new VramDetector());
  }

  public GpuCapabilitiesService(NvmlService nvml, VramDetector vramDetector) {
    this.nvml = nvml == null ? new NvmlService() : nvml;
    this.vramDetector = vramDetector == null ? new VramDetector() : vramDetector;
  }

  /**
   * Invalidates the shared nvidia-smi reading so the next {@link #snapshot()} call takes one
   * fresh reading on systems where NVML is unavailable (concurrent callers share it). On NVML
   * systems the snapshot reads NVML in-process and starts no nvidia-smi. Tempdoc 374 alpha.27 — exposed
   * so callers don't need a direct {@code VramDetector} reference.
   */
  public void invalidateNvidiaSmiCache() {
    vramDetector.invalidateCache();
  }

  /**
   * Whether the nvidia-smi tool itself is available, from its detector and without starting it.
   * Where NVML answers, the snapshot's nvidia-smi section carries NVML's figures (its source says
   * so), and this, not that section, says whether nvidia-smi is there.
   */
  public boolean nvidiaSmiToolAvailable() {
    return vramDetector.isToolAvailable();
  }

  /**
   * Snapshot WITHOUT a CUDA-functional reading (the {@link GpuCapabilities.Cuda#unknown()}
   * sentinel). Used by consumers that only need the VRAM/device axes. The composition seam that
   * also probes CUDA calls {@link #snapshot(GpuCapabilities.Cuda)} so the merged view carries
   * every GPU fact (tempdoc 587).
   */
  public GpuCapabilities snapshot() {
    return snapshot(GpuCapabilities.Cuda.unknown());
  }

  /**
   * Snapshot folding in a pre-probed CUDA-functional reading. gpu-bridge cannot reach the nvcuda
   * driver-API probe ({@code ort-common}), so the value is supplied by the one composition seam
   * that depends on both modules; this keeps gpu-bridge dependency-free of ort-common.
   */
  public GpuCapabilities snapshot(GpuCapabilities.Cuda cuda) {
    return snapshot(cuda, null);
  }

  /**
   * Snapshot whose NVML figures are the device at this PCI bus id: the pinned device the Engine
   * computes on (Spec E section 5.3, admission by bus id). Null reads device 0, as {@link
   * #snapshot()} does; on one GPU both are the same device.
   */
  public GpuCapabilities snapshotOn(String pciBusId) {
    return snapshot(GpuCapabilities.Cuda.unknown(), pciBusId);
  }

  private GpuCapabilities snapshot(GpuCapabilities.Cuda cuda, String pciBusId) {
    GpuCapabilities.Cuda cudaAxis = cuda == null ? GpuCapabilities.Cuda.unknown() : cuda;
    GpuCapabilities.Nvml nvmlSnap = pciBusId == null ? nvml.probe() : nvml.probe(pciBusId);
    if (nvmlSnap == null) {
      nvmlSnap =
          new GpuCapabilities.Nvml(
              false,
              null,
              null,
              "NVML probe unavailable.",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null);
    }

    // The nvidia-smi section. When NVML reports a GPU no nvidia-smi process starts: the section
    // carries the same figures read in-process from NVML and names that source. Without an NVML GPU
    // the shared nvidia-smi reading is used, probed at most once per 5 s.
    boolean nvmlHasGpu = nvmlSnap.available() && nvmlSnap.deviceCount() != null && nvmlSnap.deviceCount() > 0;
    GpuCapabilities.NvidiaSmi smiSnap = nvmlHasGpu ? fromNvml(nvmlSnap) : nvidiaSmi(vramDetector.reading());

    return mergeEffective(nvmlSnap, smiSnap, cudaAxis);
  }

  /** The nvidia-smi section for one shared nvidia-smi reading. */
  static GpuCapabilities.NvidiaSmi nvidiaSmi(NvidiaSmiReadings.Reading reading) {
    if (reading == null) {
      return new GpuCapabilities.NvidiaSmi(false, "no nvidia-smi reading", null, null, null, null, null,
          "Unknown (nvidia-smi not available)");
    }
    int[] parsed = DriverVersionParser.parse(reading.driverVersion());
    Long total = reading.totalBytes();
    return new GpuCapabilities.NvidiaSmi(
        reading.available(),
        reading.error(),
        reading.driverVersion(),
        parsed != null ? parsed[0] : null,
        parsed != null ? parsed[1] : null,
        total,
        reading.freeBytes(),
        total == null ? "Unknown (nvidia-smi not available)" : gigabytes(total));
  }

  /**
   * The nvidia-smi section's figures as NVML read them in-process, with no nvidia-smi process:
   * the same driver and memory fields, the source named, and each figure NVML did not give listed.
   */
  static GpuCapabilities.NvidiaSmi fromNvml(GpuCapabilities.Nvml nvml) {
    java.util.Map<String, String> unavailable = new java.util.LinkedHashMap<>();
    if (nvml.driverVersion() == null) unavailable.put("driverVersion", NOT_FROM_NVML);
    if (nvml.driverVersionMajor() == null) unavailable.put("driverVersionMajor", NOT_FROM_NVML);
    if (nvml.driverVersionMinor() == null) unavailable.put("driverVersionMinor", NOT_FROM_NVML);
    if (nvml.totalVramBytes() == null) unavailable.put("totalVramBytes", NOT_FROM_NVML);
    if (nvml.freeVramBytes() == null) unavailable.put("freeVramBytes", NOT_FROM_NVML);
    Long total = nvml.totalVramBytes();
    return new GpuCapabilities.NvidiaSmi(
        true,
        null,
        nvml.driverVersion(),
        nvml.driverVersionMajor(),
        nvml.driverVersionMinor(),
        total,
        nvml.freeVramBytes(),
        total == null ? "Unknown (" + NOT_FROM_NVML + ")" : gigabytes(total),
        GpuCapabilities.NvidiaSmi.SOURCE_NVML,
        unavailable);
  }

  private static String gigabytes(long bytes) {
    return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
  }

  /**
   * Resolves the {@link GpuCapabilities.Effective} view from the two probe snapshots plus a
   * pre-probed CUDA-functional reading, applying the source-precedence law: NVML-first (HIGH
   * confidence) when it reports at least one device, else the nvidia-smi fallback (LOW confidence)
   * when it reports positive total VRAM, else a {@code "none"}/UNKNOWN view that still carries any
   * NVML driver/device diagnostics for display. The CUDA axis is single-probe and orthogonal — it
   * rides the bundle unchanged regardless of which VRAM source wins.
   *
   * <p>Pure over its inputs and package-visible so the precedence can be pinned by unit tests
   * without spawning {@code nvidia-smi} or loading {@code nvml.dll}. {@code nvmlSnap} is required
   * non-null by {@link #snapshot()} (it substitutes an unavailable sentinel); the null-guards here
   * keep the function total for direct test callers.
   */
  static GpuCapabilities mergeEffective(
      GpuCapabilities.Nvml nvmlSnap,
      GpuCapabilities.NvidiaSmi smiSnap,
      GpuCapabilities.Cuda cuda) {
    GpuCapabilities.Cuda cudaAxis = cuda == null ? GpuCapabilities.Cuda.unknown() : cuda;
    // Effective: NVML-first; fall back to nvidia-smi.
    boolean nvmlHasGpu = nvmlSnap != null && nvmlSnap.available() && nvmlSnap.deviceCount() != null && nvmlSnap.deviceCount() > 0;
    if (nvmlHasGpu) {
      return new GpuCapabilities(
          nvmlSnap,
          smiSnap,
          new GpuCapabilities.Effective(
              true,
              "nvml",
              GpuCapabilities.Confidence.HIGH,
              nvmlSnap.driverVersion(),
              nvmlSnap.driverVersionMajor(),
              nvmlSnap.driverVersionMinor(),
              nvmlSnap.deviceCount(),
              nvmlSnap.totalVramBytes(),
              nvmlSnap.freeVramBytes(),
              nvmlSnap.usedVramBytes(),
              cudaAxis));
    }

    boolean smiHasGpu = smiSnap.available() && smiSnap.totalVramBytes() != null && smiSnap.totalVramBytes() > 0;
    if (smiHasGpu) {
      return new GpuCapabilities(
          nvmlSnap,
          smiSnap,
          new GpuCapabilities.Effective(
              true,
              "nvidia-smi",
              GpuCapabilities.Confidence.LOW,
              smiSnap.driverVersion(),
              smiSnap.driverVersionMajor(),
              smiSnap.driverVersionMinor(),
              null,
              smiSnap.totalVramBytes(),
              smiSnap.freeVramBytes(),
              null,
              cudaAxis));
    }

    return new GpuCapabilities(
        nvmlSnap,
        smiSnap,
        new GpuCapabilities.Effective(
            false,
            "none",
            GpuCapabilities.Confidence.UNKNOWN,
            nvmlSnap != null ? nvmlSnap.driverVersion() : null,
            nvmlSnap != null ? nvmlSnap.driverVersionMajor() : null,
            nvmlSnap != null ? nvmlSnap.driverVersionMinor() : null,
            nvmlSnap != null ? nvmlSnap.deviceCount() : null,
            null,
            null,
            null,
            cudaAxis));
  }
}
