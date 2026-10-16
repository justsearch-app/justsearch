/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.app.services.gpu.*;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class GpuOffUnmetStatusTest {
  @ParameterizedTest @ValueSource(strings = {"policy.gpu_acceleration_enabled", "justsearch.gpu.enabled"})
  void cpuVetoPublishesUnmetLayersAndClearsWhenTheConflictClears(String offKey) {
    var cfg = ResolvedConfig.builder().putSettings(offKey, "false")
        .putSettings("justsearch.context.size", "4096").putSettings("justsearch.gpu.layers", "12").build();
    var device = new CudaDevice(0, Optional.empty(), Optional.empty(), Optional.empty(), 12_282L << 20);
    var facts = new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080,
        List.of(device), Optional.of(device));
    var resolution = new AtomicReference<>(GpuProfileResolver.resolve(cfg, facts, Map.of(),
        Set.of(ChatModelProfile.STANDARD)));
    var assembler = new OperatingStateAssembler();
    assembler.setDevicePlan(resolution::get);
    var observed = "2026-10-10T01:00:00Z";
    var ready = new LifecycleSnapshotV2.Component(ComponentState.READY, null, observed);
    var lifecycle = new LifecycleSnapshotV2(LifecycleSnapshotV2.SCHEMA_VERSION, observed,
        new LifecycleSnapshotV2.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY, null, null),
        new LifecycleSnapshotV2.Components(ready, ready, ready, ready));
    var inference = new InferenceRuntimeView("ONLINE", null, false, null,
        new LifecycleCounters(0L, 0L, 0L), true, "Healthy", "", "", "");
    var view = assembler.assemble(lifecycle, inference, PowerStatusView.unknown(), null, 0L,
        false, 2_000L, Instant.parse(observed), null);
    assertEquals(Profile.NO_GPU, view.gpu().profile());
    assertEquals(GpuMemorySource.NONE, view.gpu().source());
    assertEquals(12, cfg.ai().gpuLayers(), "the saved request stays explicit");
    assertEquals(4096, cfg.ai().contextSize(), "CPU launch can honour this context");
    assertEquals(1, view.degradations().size(), "positive layers cannot be met by CPU placement");
    var unmet = view.degradations().getFirst();
    assertEquals("user_setting_unmet", unmet.code().token());
    assertEquals("layers", unmet.cause(), "context and GPU off are both met");
    assertEquals("inference.setting_unmet", unmet.reasonCode());
    resolution.set(GpuProfileResolver.resolve(ResolvedConfig.builder().putSettings(offKey, "false")
        .putSettings("justsearch.context.size", "4096").putSettings("justsearch.gpu.layers", "0")
        .build(), facts, Map.of(), Set.of(ChatModelProfile.STANDARD)));
    assertEquals(List.of(), assembler.assemble(lifecycle, inference, PowerStatusView.unknown(),
        null, 0L, false, 2_000L, Instant.parse(observed), null).degradations());
  }
}
