/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.gpu.memory.GpuAdapters;
import io.justsearch.ort.GpuDriverApiProbe;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Spec E check E-U6: the device pinning rule (section 5.2) and the LUID / bus-id / UUID maps. */
final class DevicePinningTest {

  private static final long GIB = 1024L * 1024 * 1024;
  private static final int NVIDIA = 0x10DE;
  private static final int INTEL = 0x8086;

  private static final String LUID_A = "luid_0x00000000_0x0000d7a2";
  private static final String LUID_B = "luid_0x00000000_0x0000e101";
  private static final String LUID_IGPU = "luid_0x00000000_0x00001111";

  private static CudaDevice cuda(int ordinal, String luid) {
    return new CudaDevice(
        ordinal,
        Optional.ofNullable(luid),
        Optional.of("GPU-0000000" + ordinal + "-0000-0000-0000-000000000000"),
        Optional.of("0000:0" + (ordinal + 1) + ":00.0"),
        12 * GIB);
  }

  private static GpuAdapters.Adapter adapter(String luid, int vendor, long dedicated) {
    return new GpuAdapters.Adapter(luid, vendor, dedicated, false);
  }

  @Test
  void theLargestMappedNvidiaAdapterWins() {
    List<CudaDevice> devices = List.of(cuda(0, LUID_A), cuda(1, LUID_B));
    List<GpuAdapters.Adapter> adapters =
        List.of(adapter(LUID_A, NVIDIA, 8 * GIB), adapter(LUID_B, NVIDIA, 16 * GIB));

    assertEquals(Optional.of(devices.get(1)), DevicePinning.choose(devices, adapters));
  }

  @Test
  void aTieGoesToDxgiEnumerationOrderNotCudaOrder() {
    // CUDA lists B first (FASTEST_FIRST); DXGI lists A first. The tie goes to DXGI's order.
    List<CudaDevice> devices = List.of(cuda(0, LUID_B), cuda(1, LUID_A));
    List<GpuAdapters.Adapter> adapters =
        List.of(adapter(LUID_A, NVIDIA, 12 * GIB), adapter(LUID_B, NVIDIA, 12 * GIB));

    assertEquals(Optional.of(devices.get(1)), DevicePinning.choose(devices, adapters));
  }

  @Test
  void aHybridLaptopsIntegratedGpuNeverCounts() {
    List<CudaDevice> devices = List.of(cuda(0, LUID_A));
    List<GpuAdapters.Adapter> adapters =
        List.of(adapter(LUID_IGPU, INTEL, 32 * GIB), adapter(LUID_A, NVIDIA, 6 * GIB));

    assertEquals(Optional.of(devices.get(0)), DevicePinning.choose(devices, adapters));
  }

  @Test
  void softwareAndUnmappedAdaptersAreNotCandidates() {
    List<CudaDevice> devices = List.of(cuda(0, LUID_A), cuda(1, LUID_B));
    List<GpuAdapters.Adapter> adapters =
        List.of(
            new GpuAdapters.Adapter(LUID_B, NVIDIA, 64 * GIB, true),
            adapter(LUID_IGPU, NVIDIA, 48 * GIB),
            adapter(LUID_A, NVIDIA, 8 * GIB));

    assertEquals(Optional.of(devices.get(0)), DevicePinning.choose(devices, adapters));
  }

  @Test
  void oneDeviceWithoutALuidIsOrdinalZero() {
    // A TCC device, or Linux: no LUID and no DXGI adapter for it.
    List<CudaDevice> devices = List.of(cuda(0, null));

    assertEquals(Optional.of(devices.get(0)), DevicePinning.choose(devices, List.of()));
  }

  @Test
  void severalDevicesWithoutAMapAreUnresolved() {
    List<CudaDevice> devices = List.of(cuda(0, null), cuda(1, null));

    assertEquals(
        Optional.empty(),
        DevicePinning.choose(devices, List.of(adapter(LUID_A, NVIDIA, 12 * GIB))));
    assertEquals(Optional.empty(), DevicePinning.choose(List.of(), List.of()));
  }

  @Test
  void probeOutcomesMapToFactsStatus() {
    GpuDriverApiProbe.Device device =
        new GpuDriverApiProbe.Device(
            0, Optional.of(LUID_A), Optional.of("GPU-x"), Optional.of("0000:01:00.0"), 12 * GIB);
    DeviceFacts ok =
        DevicePinning.facts(
            new GpuDriverApiProbe.Result(
                true, 1, 12080, "ok", GpuDriverApiProbe.Status.OK, 0, List.of(device)),
            List.of(adapter(LUID_A, NVIDIA, 12 * GIB)));
    assertEquals(DeviceFacts.ProbeStatus.RAN_OK, ok.status());
    assertEquals(0, ok.pinned().orElseThrow().ordinal());
    assertEquals(LUID_A, ok.pinned().orElseThrow().luid().orElseThrow());

    DeviceFacts none =
        DevicePinning.facts(
            new GpuDriverApiProbe.Result(
                false, 0, 0, "cuInit returned 100", GpuDriverApiProbe.Status.NO_DEVICE, 100,
                List.of()),
            List.of());
    assertEquals(DeviceFacts.ProbeStatus.RAN_NO_DEVICE, none.status());
    assertEquals(100, none.code());
    assertEquals(Optional.empty(), none.pinned());

    DeviceFacts threw =
        DevicePinning.facts(
            new GpuDriverApiProbe.Result(
                false, 0, 0, "binding failed", GpuDriverApiProbe.Status.FAILED, 0, List.of()),
            List.of());
    assertEquals(DeviceFacts.ProbeStatus.THREW, threw.status());
  }

  @Test
  void aDriverBelowTheLlamaRuntimesCudaMajorHandsLlamaServerNoDevice() {
    GpuDriverApiProbe.Device device =
        new GpuDriverApiProbe.Device(
            0, Optional.of(LUID_A), Optional.of("GPU-x"), Optional.of("0000:01:00.0"), 12 * GIB);
    List<GpuAdapters.Adapter> adapters = List.of(adapter(LUID_A, NVIDIA, 12 * GIB));

    // A driver whose CUDA API is 11.8 cannot run the bundled cuda12 llama-server runtime.
    DeviceFacts oldDriver =
        DevicePinning.facts(
            new GpuDriverApiProbe.Result(
                true, 1, 11080, "ok", GpuDriverApiProbe.Status.OK, 0, List.of(device)),
            adapters);
    assertEquals(
        Optional.empty(),
        oldDriver.pinnedUuid(),
        "below CUDA 12 llama-server is launched unpinned, as before the pin existed");

    DeviceFacts cuda12 =
        DevicePinning.facts(
            new GpuDriverApiProbe.Result(
                true, 1, 12000, "ok", GpuDriverApiProbe.Status.OK, 0, List.of(device)),
            adapters);
    assertEquals(Optional.of("GPU-x"), cuda12.pinnedUuid(), "CUDA 12.0 is enough");
  }

  @Test
  void thePinnedDeviceMapsToReaderBusIdAndUuid() {
    CudaDevice pinned = cuda(1, LUID_B);
    DeviceFacts facts =
        new DeviceFacts(
            DeviceFacts.ProbeStatus.RAN_OK, 0, 12080, List.of(cuda(0, LUID_A), pinned),
            Optional.of(pinned));

    assertEquals(Optional.of(new GpuDevice(LUID_B, OptionalInt.of(1))), facts.pinnedGpuDevice());
    assertEquals(Optional.of("0000:02:00.0"), facts.pinnedPciBusId());
    assertEquals(pinned.uuid(), facts.pinnedUuid());

    CudaDevice noLuid = cuda(0, null);
    DeviceFacts tcc =
        new DeviceFacts(
            DeviceFacts.ProbeStatus.RAN_OK, 0, 12080, List.of(noLuid), Optional.of(noLuid));
    assertEquals(Optional.empty(), tcc.pinnedGpuDevice(), "the reader maps a single adapter itself");
    assertEquals(Optional.empty(), DeviceFacts.notRun().pinnedPciBusId());
  }

  @Test
  void thePinnedDeviceMustBeOneOfTheDevices() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DeviceFacts(
                DeviceFacts.ProbeStatus.RAN_OK, 0, 12080, List.of(cuda(0, LUID_A)),
                Optional.of(cuda(1, LUID_B))));
  }

  @Test
  void thePinnedOrdinalIsEveryOrtRolesDefaultDevice() {
    CudaDevice pinned = cuda(1, LUID_B);
    DeviceFacts facts =
        new DeviceFacts(
            DeviceFacts.ProbeStatus.RAN_OK, 0, 12080, List.of(cuda(0, LUID_A), pinned),
            Optional.of(pinned));
    Map<String, String> defaults = DevicePinning.ortDeviceDefaults(facts);
    assertEquals(6, defaults.size());
    assertTrue(defaults.values().stream().allMatch("1"::equals), defaults.toString());

    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(defaults);
    ResolvedConfig.Ai ai = builder.build().ai();
    assertEquals(1, ai.embedding().gpuDeviceId());
    assertEquals(1, ai.splade().gpuDeviceId());
    assertEquals(1, ai.ner().gpuDeviceId());
    assertEquals(1, ai.bgeM3().gpuDeviceId());
    assertEquals(1, ai.reranker().gpuDeviceId());
    assertEquals(1, ai.reranker().chunks().gpuDeviceId());
  }

  @Test
  void anExplicitRoleDeviceIdKeepsItsValue() {
    CudaDevice pinned = cuda(1, LUID_B);
    DeviceFacts facts =
        new DeviceFacts(
            DeviceFacts.ProbeStatus.RAN_OK, 0, 12080, List.of(cuda(0, LUID_A), pinned),
            Optional.of(pinned));
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(DevicePinning.ortDeviceDefaults(facts));
    // Settings (ordinal 300) are an explicit source; the auto-detected pin is ordinal 150.
    builder.putSettings("justsearch.splade.gpu_device_id", "0");
    ResolvedConfig.Ai ai = builder.build().ai();
    assertEquals(0, ai.splade().gpuDeviceId());
    assertEquals(1, ai.embedding().gpuDeviceId());
  }

  @Test
  void nothingPinnedLeavesTheDefaults() {
    assertEquals(Map.of(), DevicePinning.ortDeviceDefaults(DeviceFacts.notRun()));
  }
}
