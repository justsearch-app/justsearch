/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LlamaDevicePlanTest {
  private static final String UUID = "GPU-pinned-adapter";

  @Test
  void cpuPolicyOverridesPinningLayersAndProjectorDefaults() {
    for (String key : List.of("justsearch.gpu.enabled", "policy.gpu_acceleration_enabled")) {
      var builder = ResolvedConfig.builder();
      builder.putSettings(key, "false");
      var plan = LlamaDevicePlan.resolve(builder.build(), 99, Optional.of(UUID));
      assertEquals(List.of("-ngl", "0"), plan.layerFlags());
      assertEquals(List.of("--device", "none"), plan.deviceFlags());
      assertEquals(List.of("--no-mmproj-offload"), plan.projectorFlags());
      assertEquals(Map.of("CUDA_VISIBLE_DEVICES", "-1"), plan.environment());
    }
  }

  @Test
  void explicitZeroLayersHidesDevicesEvenWithoutAPolicyVetoOrProjector() {
    var plan = LlamaDevicePlan.resolve(ResolvedConfig.builder().build(), 0, Optional.of(UUID));
    assertEquals(Map.of("CUDA_VISIBLE_DEVICES", "-1"), plan.environment());
    assertFalse(plan.projectorOffload());
  }

  @Test
  void explicitResolvedLayersWinOverAnActivationDefaultOrCallerValue() {
    for (int layers : new int[] {0, 12}) {
      var builder = ResolvedConfig.builder();
      builder.putSettings("justsearch.gpu.layers", Integer.toString(layers));
      var plan = LlamaDevicePlan.resolve(builder.build(), 99, Optional.of(UUID));
      assertEquals(layers, plan.gpuLayers());
      assertEquals(layers == 0 ? "-1" : UUID, plan.environment().get("CUDA_VISIBLE_DEVICES"));
    }
  }

  @Test
  void aSelectedCpuLaunchCannotBeRaisedToGpuByAResolvedLayerSetting() {
    var builder = ResolvedConfig.builder();
    builder.putSettings("justsearch.gpu.layers", "99");
    var plan = LlamaDevicePlan.resolve(builder.build(), 0, Optional.of(UUID));
    assertEquals(0, plan.gpuLayers());
    assertEquals(Map.of("CUDA_VISIBLE_DEVICES", "-1"), plan.environment());
  }

  @Test
  void gpuPlacementRetainsPinningAndExplicitLayers() {
    var builder = ResolvedConfig.builder();
    builder.putSettings("justsearch.gpu.enabled", "true");
    builder.putSettings("justsearch.mmproj.offload", "false");
    var plan = LlamaDevicePlan.resolve(builder.build(), 12, Optional.of(UUID));
    assertEquals(List.of("-ngl", "12"), plan.layerFlags());
    assertEquals(List.of("--device", "CUDA0", "-sm", "none"), plan.deviceFlags());
    assertEquals(Map.of("CUDA_VISIBLE_DEVICES", UUID), plan.environment());
    assertEquals(List.of("--no-mmproj-offload"), plan.projectorFlags());
  }

  @Test
  void unpinnedGpuRetainsInheritedDeviceOrder() {
    var plan = LlamaDevicePlan.resolve(ResolvedConfig.builder().build(), 99, Optional.empty());
    assertEquals(Map.of(), plan.environment());
    assertEquals(List.of(), plan.deviceFlags());
    assertTrue(plan.projectorOffload());
  }
}
