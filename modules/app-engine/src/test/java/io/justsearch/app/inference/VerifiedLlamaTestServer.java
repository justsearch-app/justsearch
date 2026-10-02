/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.mockito.MockedConstruction;

/** Process seam only: lifecycle transitions, verification and registry publication remain real. */
public final class VerifiedLlamaTestServer implements AutoCloseable {
  private final AtomicReference<LlamaServerOps.StartResult> active = new AtomicReference<>();
  private final AtomicReference<LlamaServerOps.StartRequest> retained = new AtomicReference<>();
  private LlamaServerOps.StartResult failedOwner;
  private final List<LlamaServerOps.StartRequest> starts = new ArrayList<>();
  private final List<List<String>> commands = new ArrayList<>();
  private final MockedConstruction<GpuCapabilitiesService> gpuConstruction;
  private final MockedConstruction<LlamaServerOps> construction;
  private Consumer<BooleanSupplier> failure;
  private Runnable healthProbe = () -> {};
  private boolean rejectWitness;
  private boolean rejectHealth;
  private boolean verified;

  @SuppressWarnings("unchecked") // Constructor's physical failure callback is typed by the manager.
  public VerifiedLlamaTestServer() {
    gpuConstruction = mockConstruction(GpuCapabilitiesService.class, (gpu, context) -> {
      var effective = mock(GpuCapabilities.Effective.class);
      when(effective.totalVramBytes()).thenReturn(16L * 1024 * 1024 * 1024);
      when(gpu.snapshot()).thenReturn(new GpuCapabilities(null, null, effective));
    });
    construction = mockConstruction(LlamaServerOps.class, (server, context) -> {
      failure = (Consumer<BooleanSupplier>) context.arguments().get(6);
      doAnswer(invocation -> {
        retained.set(invocation.getArgument(0));
        return null;
      }).when(server).retainAttemptedStartRequest(any());
      when(server.startLlamaServer(any())).thenAnswer(invocation -> {
        LlamaServerOps.StartRequest request = invocation.getArgument(0);
        retained.set(request);
        starts.add(request);
        // Exercise production argument assembly as well as effective layer selection. Only
        // process creation and probes are replaced; dropping real cache/GPU flags must fail.
        commands.add(LlamaServerOps.buildLaunchCommand(request.context().inference(),
            request.context().resolved(), request.effectiveGpuLayers(),
            ContextWindowPolicy.override(request.context().inference().contextSize(), null)));
        verified = false;
        String hash = ManagedLlamaConfigIdentity.declaredHash(request.context().inference(),
            request.context().resolved(), request.effectiveGpuLayers());
        var result = new LlamaServerOps.StartResult(request.context(), request.adoptionPolicy(),
            LlamaServerOps.StartDisposition.LAUNCHED_MANAGED, rejectWitness ? "wrong" : hash);
        rejectWitness = false;
        active.set(result);
        return result;
      });
      when(server.activeStartResult()).thenAnswer(ignored -> Optional.ofNullable(active.get()));
      when(server.activeManagedCandidateAlive(any())).thenAnswer(invocation ->
          active.get() == invocation.getArgument(0));
      doAnswer(ignored -> { active.set(null); return null; }).when(server).stopLlamaServer();
      doAnswer(ignored -> { checkHealth(); return null; }).when(server).waitForServerHealth(any());
      doAnswer(ignored -> { checkHealth(); return null; }).when(server).waitForServerHealthOnce(any());
      doAnswer(ignored -> { retained.set(null); verified = true; return null; })
          .when(server).acceptVerifiedStart(any(), any());
      when(server.recoveryStartRequest()).thenAnswer(ignored -> {
        var owner = active.get();
        return owner == null ? Optional.ofNullable(retained.get()) : Optional.of(
            new LlamaServerOps.StartRequest(owner.context(), owner.adoptionPolicy()));
      });
      when(server.reserveRecoveryStart(any())).thenAnswer(invocation -> {
        LlamaServerOps.StartRequest request = invocation.getArgument(0);
        if (!owns(request)) return false;
        retained.set(request);
        return true;
      });
      when(server.ownsRecoveryAttempt(any())).thenAnswer(invocation -> owns(invocation.getArgument(0)));
      when(server.componentRecoveryPending()).thenAnswer(ignored -> retained.get() != null
          || active.get() != null && active.get() == failedOwner);
    });
  }

  private boolean owns(LlamaServerOps.StartRequest request) {
    var owner = active.get();
    return owner == null ? retained.get() == request : owner.context() == request.context()
        && owner.adoptionPolicy() == request.adoptionPolicy();
  }

  private void checkHealth() {
    healthProbe.run();
    if (rejectHealth) {
      rejectHealth = false;
      throw new IllegalStateException("health refused");
    }
  }

  public void onHealth(Runnable probe) { healthProbe = probe; }
  public void rejectNextWitness() { rejectWitness = true; }
  public void rejectNextHealth() { rejectHealth = true; }
  public boolean verified() { return verified; }
  public int starts() { return starts.size(); }
  public ResolvedConfig lastResolved() { return starts.getLast().context().resolved(); }
  public List<String> lastCommand() { return commands.getLast(); }
  public void failServing() {
    failedOwner = active.get();
    failure.accept(() -> true);
  }
  @Override public void close() {
    try { construction.close(); }
    finally { gpuConstruction.close(); }
  }
}
