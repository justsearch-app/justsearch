/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import io.justsearch.gpu.VramDetector;

/** v3: Produces a merged GPU capability snapshot (NVML-first, with nvidia-smi fallback). */
public final class GpuCapabilitiesService {
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
   * Invalidates the nvidia-smi fallback cache so the next {@link #snapshot()} call
   * re-shells nvidia-smi on systems where NVML is unavailable. No-op on NVML systems
   * where the snapshot doesn't consult VramDetector. Tempdoc 374 alpha.27 — exposed
   * so callers don't need a direct {@code VramDetector} reference.
   */
  public void invalidateNvidiaSmiCache() {
    vramDetector.invalidateCache();
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

    // nvidia-smi fallback (best-effort).
    Long smiTotal = null;
    long total = vramDetector.getTotalVramBytes();
    if (total >= 0) smiTotal = total;
    Long smiFree = null;
    long free = vramDetector.getAvailableVramBytes();
    if (free >= 0) smiFree = free;
    String smiDesc = vramDetector.getVramDescription();

    String smiDriver = vramDetector.getDriverVersion();
    int[] smiParsed = DriverVersionParser.parse(smiDriver);
    Integer smiMajor = smiParsed != null ? smiParsed[0] : null;
    Integer smiMinor = smiParsed != null ? smiParsed[1] : null;

    boolean smiAvailable = (smiTotal != null && smiTotal > 0) || (smiDriver != null && !smiDriver.isBlank());
    GpuCapabilities.NvidiaSmi smiSnap =
        new GpuCapabilities.NvidiaSmi(
            smiAvailable,
            null,
            smiDriver,
            smiMajor,
            smiMinor,
            smiTotal,
            smiFree,
            smiDesc);

    return mergeEffective(nvmlSnap, smiSnap, cudaAxis);
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
