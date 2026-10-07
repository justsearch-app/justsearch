/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.operating.DegradationCode;
import io.justsearch.app.api.status.operating.DegradationScope;
import io.justsearch.app.api.status.operating.OperatingDegradationView;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import io.justsearch.app.api.status.operating.OperatingStateView;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.app.api.status.operating.ProfileSource;
import io.justsearch.app.services.gpu.CudaDevice;
import io.justsearch.app.services.gpu.DeviceFacts;
import io.justsearch.app.services.gpu.GpuProfileResolver;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import io.justsearch.ort.EncoderRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Spec E step E7b: the operating gpu block publishes the device-memory plan's profile and source,
 * and lists {@code user_setting_unmet} while an explicit setting outranks a plan it breaks (P4).
 */
final class OperatingGpuProfileTest {

  private static final Instant NOW = Instant.parse("2026-10-07T10:15:02.200Z");
  private static final Map<EncoderRole, Double> WEIGHTS = Map.of(
      EncoderRole.EMBEDDING, 598.3, EncoderRole.SPLADE, 474.2, EncoderRole.NER, 257.2,
      EncoderRole.RERANKER, 600.3);

  @Test
  void theGpuBlockPublishesThePlansProfileAndAnUnmetSettingIsListed() {
    var plan = new AtomicReference<>(resolve(12_282, Map.of()));
    var assembler = new OperatingStateAssembler();
    assembler.setDevicePlan(plan::get);

    OperatingStateView measured = assemble(assembler, "2026-10-07T10:15:02.000Z");
    assertEquals(Profile.P12, measured.gpu().profile());
    assertEquals(ProfileSource.MEASURED, measured.gpu().profileSource());
    assertEquals(GpuMemorySource.NONE, measured.gpu().source(), "the reading is unchanged");
    assertEquals(List.of(), measured.degradations());

    // An explicit 128k window on the 9B breaks P12: the setting is kept and listed as unmet.
    plan.set(resolve(12_282, Map.of("justsearch.context.size", "131072")));
    OperatingStateView unmet = assemble(assembler, "2026-10-07T10:15:04.000Z");
    assertEquals(Profile.P12, unmet.gpu().profile(), "the profile is kept");
    assertEquals(ProfileSource.USER_OVERRIDE, unmet.gpu().profileSource());
    OperatingDegradationView entry = unmet.degradations().getFirst();
    assertEquals(DegradationCode.USER_SETTING_UNMET, entry.code());
    assertEquals("context,encoder_memory", entry.cause());
    assertEquals(DegradationScope.RUNTIME, entry.scope());
    assertEquals("inference.setting_unmet", entry.reasonCode());
    assertEquals("2026-10-07T10:15:04.000Z", entry.since());
    assertEquals(1L, entry.count());
    assertEquals(measured.seq() + 1, unmet.seq(), "a listed degradation is content");

    OperatingStateView again = assemble(assembler, "2026-10-07T10:15:06.000Z");
    OperatingDegradationView same = again.degradations().getFirst();
    assertEquals("2026-10-07T10:15:04.000Z", same.since(), "one episode keeps its since");
    assertEquals("2026-10-07T10:15:06.000Z", same.lastSeenAt());
    assertEquals(unmet.seq(), again.seq(), "a later sighting is not content");

    plan.set(resolve(12_282, Map.of()));
    assertEquals(List.of(), assemble(assembler, "2026-10-07T10:15:08.000Z").degradations());
    plan.set(resolve(12_282, Map.of("justsearch.context.size", "131072")));
    assertEquals(2L, assemble(assembler, "2026-10-07T10:15:10.000Z").degradations().getFirst()
        .count(), "a new episode is counted");
  }

  @Test
  void belowP8ReadsAsTheP8ClassSaysSoAndNoPlanReadsAsUnresolved() {
    var plan = new AtomicReference<>(resolve(6_144, Map.of()));
    var assembler = new OperatingStateAssembler();
    assembler.setDevicePlan(plan::get);
    OperatingStateView below = assemble(assembler, "2026-10-07T10:15:02.000Z");
    assertEquals(Profile.P8, below.gpu().profile(), "the smallest class the profile enum has");
    assertEquals(ProfileSource.MEASURED, below.gpu().profileSource());
    // The device is below the P8 boundary: today's constants, and status says so.
    assertEquals(1, below.degradations().size());
    OperatingDegradationView entry = below.degradations().getFirst();
    assertEquals(DegradationCode.DEVICE_BELOW_P8, entry.code());
    assertEquals("device_memory", entry.cause());
    assertEquals(DegradationScope.RUNTIME, entry.scope());
    assertEquals("inference.device_below_p8", entry.reasonCode());
    assertEquals("2026-10-07T10:15:02.000Z", entry.since());
    assertEquals(1L, entry.count());

    // A P8 device with the 9B (above the boundary, without COMPACT) is not below it.
    plan.set(resolve(10_240, Map.of()));
    OperatingStateView p8 = assemble(assembler, "2026-10-07T10:15:03.000Z");
    assertEquals(Profile.P8, p8.gpu().profile());
    assertEquals(List.of(), p8.degradations());
    assertEquals(below.seq() + 1, p8.seq(), "the entry clearing is content");

    plan.set(GpuProfileResolver.Resolution.unresolved());
    OperatingStateView none = assemble(assembler, "2026-10-07T10:15:04.000Z");
    OperatingGpuView gpu = none.gpu();
    assertEquals(Profile.UNRESOLVED, gpu.profile());
    assertNull(gpu.profileSource());
    assertEquals(List.of(), none.degradations());
  }

  private static GpuProfileResolver.Resolution resolve(double deviceMiB, Map<String, String> user) {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    user.forEach(builder::putSettings);
    CudaDevice device = new CudaDevice(0, Optional.empty(), Optional.empty(), Optional.empty(),
        (long) (deviceMiB * 1024 * 1024));
    DeviceFacts facts = new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080,
        List.of(device), Optional.of(device));
    return GpuProfileResolver.resolve(builder.build(), facts, WEIGHTS,
        Set.of(ChatModelProfile.STANDARD));
  }

  private static OperatingStateView assemble(OperatingStateAssembler assembler, String observed) {
    var ready = new LifecycleSnapshotV2.Component(ComponentState.READY, null, "2026-10-07T10:00:00Z");
    var lifecycle = new LifecycleSnapshotV2(LifecycleSnapshotV2.SCHEMA_VERSION, observed,
        new LifecycleSnapshotV2.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY, null, null),
        new LifecycleSnapshotV2.Components(ready, ready, ready, ready));
    var inference = new InferenceRuntimeView("ONLINE", null, false, null,
        new LifecycleCounters(0L, 0L, 0L), true, "Healthy", "", "", "");
    return assembler.assemble(lifecycle, inference, PowerStatusView.unknown(), null, 0L, false,
        2_000L, NOW, null);
  }
}
