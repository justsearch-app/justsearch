/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One captured device policy for a managed chat launch or activation self-test.
 *
 * <p>Zero layers is a CPU launch, including its compute, KV and vision projector. The bundled
 * b8571 supports device none and no-mmproj-offload. Hiding CUDA devices from the child prevents
 * even backend initialization from allocating CUDA buffers; argv also disables device offload.
 * A pinned GPU remains CUDA0 in the child's restricted device namespace.
 */
public record LlamaDevicePlan(int gpuLayers, boolean projectorOffload, Map<String, String> environment) {
  public static final String CPU_PROJECTOR_FLAG = "--no-mmproj-offload";
  public static final List<String> PINNED_DEVICE_FLAGS =
      List.of("--device", "CUDA0", "-sm", "none");

  public LlamaDevicePlan {
    environment = Map.copyOf(environment);
    if (gpuLayers < 0) throw new IllegalArgumentException("negative GPU layers");
    if (gpuLayers == 0 && (projectorOffload
        || !"-1".equals(environment.get("CUDA_VISIBLE_DEVICES")))) {
      throw new IllegalArgumentException("CPU launch must hide CUDA and keep the projector on CPU");
    }
  }

  public static LlamaDevicePlan resolve(
      ResolvedConfig config, int requestedLayers, Optional<String> pinnedUuid) {
    return resolve(config, requestedLayers, pinnedEnvironment(pinnedUuid));
  }

  static LlamaDevicePlan resolve(
      ResolvedConfig config, int requestedLayers, Map<String, String> pinnedEnvironment) {
    var master = config.resolution("justsearch.gpu.enabled");
    boolean explicitOff = master != null && master.isResolved()
        && master.sourceOrdinal() > ResolvedConfigBuilder.ORDINAL_AUTO_DETECT
        && !config.ai().masterGpuEnabled();
    var layers = config.resolution("justsearch.gpu.layers");
    int effectiveLayers = requestedLayers == 0 ? 0 : layers != null && layers.isResolved()
        && layers.sourceOrdinal() > ResolvedConfigBuilder.ORDINAL_AUTO_DETECT
        ? config.ai().gpuLayers() : requestedLayers;
    // The Engine's facts/profile decision also owns placement. Positive user preferences remain
    // in config (and the profile reports unmet layers), but cannot create a nonexistent device.
    boolean allowed = !"no_gpu".equals(config.ai().gpuMemoryPlan())
        && !explicitOff && config.ai().gpuAccelerationAllowed();
    int selectedLayers = allowed ? effectiveLayers : 0;
    return selectedLayers == 0
        ? new LlamaDevicePlan(0, false, Map.of("CUDA_VISIBLE_DEVICES", "-1"))
        : new LlamaDevicePlan(selectedLayers, config.ai().mmprojOffload(), pinnedEnvironment);
  }

  static Map<String, String> pinnedEnvironment(Optional<String> pinnedUuid) {
    return pinnedUuid.filter(uuid -> !uuid.isBlank())
        .map(uuid -> Map.of("CUDA_VISIBLE_DEVICES", uuid)).orElse(Map.of());
  }

  public List<String> deviceFlags() {
    if (gpuLayers == 0) return List.of("--device", "none");
    return environment.isEmpty() ? List.of() : PINNED_DEVICE_FLAGS;
  }

  public List<String> layerFlags() {
    return layerFlags(gpuLayers);
  }

  static List<String> layerFlags(int gpuLayers) {
    return List.of("-ngl", Integer.toString(gpuLayers));
  }

  public List<String> projectorFlags() {
    return projectorOffload ? List.of() : List.of(CPU_PROJECTOR_FLAG);
  }
}
