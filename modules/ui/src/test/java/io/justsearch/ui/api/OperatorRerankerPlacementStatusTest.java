/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

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

final class OperatorRerankerPlacementStatusTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"4096,P8", "8192,P8", "12282,P12", "16384,P16_PLUS"})
  void operatorCpuChatWithCudaRerankerReportsGpuPlacement(long memoryMiB, Profile expected) {
    var cfg = ResolvedConfig.builder().putSettings("justsearch.gpu.enabled", "false").build();
    var device = new CudaDevice(0, Optional.empty(), Optional.empty(), Optional.empty(), memoryMiB << 20);
    var facts = new DeviceFacts(DeviceFacts.ProbeStatus.RAN_OK, 0, 12_080,
        List.of(device), Optional.of(device));
    var resolution = new AtomicReference<>(GpuProfileResolver.resolve(cfg, facts,
        Map.of(io.justsearch.ort.EncoderRole.RERANKER, 600.3), Set.of(ChatModelProfile.STANDARD)));
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
    assertEquals(expected, view.gpu().profile(), "GPU reranker must not be labelled all-CPU");
    assertEquals(Profile.NO_GPU, resolution.get().statusProfile(), "chat retains its CPU plan");
    assertTrue(cfg.ai().reranker().gpuEnabled(), "documented operator exemption");
  }
}
