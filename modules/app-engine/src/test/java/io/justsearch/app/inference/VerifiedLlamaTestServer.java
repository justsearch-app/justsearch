/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import io.justsearch.configuration.resolved.ResolvedConfig;
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
  private final List<LlamaServerOps.StartRequest> starts = new ArrayList<>();
  private final MockedConstruction<LlamaServerOps> construction;
  private Consumer<BooleanSupplier> failure;
  private Runnable healthProbe = () -> {};
  private boolean rejectWitness;
  private boolean rejectHealth;
  private boolean verified;

  @SuppressWarnings("unchecked") // Constructor's physical failure callback is typed by the manager.
  public VerifiedLlamaTestServer() {
    construction = mockConstruction(LlamaServerOps.class, (server, context) -> {
      failure = (Consumer<BooleanSupplier>) context.arguments().get(6);
      when(server.startLlamaServer(any())).thenAnswer(invocation -> {
        LlamaServerOps.StartRequest request = invocation.getArgument(0);
        starts.add(request);
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
      doAnswer(ignored -> { verified = true; return null; })
          .when(server).acceptVerifiedStart(any(), any());
      when(server.recoveryStartRequest()).thenAnswer(ignored -> Optional.ofNullable(active.get())
          .map(owner -> new LlamaServerOps.StartRequest(owner.context(), owner.adoptionPolicy())));
      when(server.reserveRecoveryStart(any())).thenAnswer(invocation -> owns(invocation.getArgument(0)));
      when(server.ownsRecoveryAttempt(any())).thenAnswer(invocation -> owns(invocation.getArgument(0)));
    });
  }

  private boolean owns(LlamaServerOps.StartRequest request) {
    var owner = active.get();
    return owner != null && owner.context() == request.context()
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
  public void failServing() { failure.accept(() -> true); }
  @Override public void close() { construction.close(); }
}
