/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtSession;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated installed-library proof that an issued real CUDA session survives retirement. */
public final class InstalledGpuLeaseRound {
  private InstalledGpuLeaseRound() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Expected CPU and FP16 CUDA model paths");
    Path cpu = Path.of(args[0]).toAbsolutePath().normalize();
    Path gpu = Path.of(args[1]).toAbsolutePath().normalize();
    require(Files.isRegularFile(cpu) && Files.isRegularFile(gpu),
        "exact installed CPU and CUDA model files are required");
    var variant = VariantSelection.optimal(gpu, ModelPrecision.FP16, ExecutionProvider.CUDA);
    var policy = new ModelSessionPolicy(variant,
        new ModelSessionPolicy.Gpu(4L * 1024 * 1024 * 1024, 0, Optional.empty()),
        new ModelSessionPolicy.Cpu(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT),
        new ModelSessionPolicy.Lifecycle(true, false, 0L),
        new ModelSessionPolicy.RunOptions(true));
    var composition = new Composition(RuntimePolicy.defaults(), policy,
        new ModelArtifacts(cpu, gpu));
    SessionHandle handle = OrtSessionAssembler.buildManager(
        "installed-gpu-lease", composition, () -> true);
    SessionHandle.Lease held = null;
    Thread closer = null;
    try {
      held = handle.acquire(request());
      require(!held.isCpu() && handle.isGpuAvailable(),
          "the installed FP16 model must issue a real CUDA session, not CPU fallback");
      Set<String> inputs = held.session().getInputNames();
      require(!inputs.isEmpty(), "the issued CUDA session has no input names");
      AtomicReference<Throwable> closeFailure = new AtomicReference<>();
      closer = new Thread(() -> {
        try { handle.close(); }
        catch (Throwable failure) { closeFailure.set(failure); }
      }, "installed-gpu-lease-retire");
      closer.start();
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (handle.retirementStatus() == SessionHandle.RetirementStatus.ACTIVE
          && System.nanoTime() < deadline) Thread.onSpinWait();
      require(handle.retirementStatus() == SessionHandle.RetirementStatus.RETIRING,
          "retirement did not wait for the issued CUDA lease");
      require(inputs.equals(held.session().getInputNames()),
          "the held exact CUDA session closed before its lease ended");
      try {
        handle.acquire(request());
        throw new AssertionError("a new lease was issued after CUDA retirement began");
      } catch (SessionRetiredException expected) {
        // The already-issued lease remains usable, while fresh acquisitions refuse.
      }
      held.close();
      held = null;
      closer.join(Duration.ofSeconds(10));
      require(!closer.isAlive() && closeFailure.get() == null,
          "CUDA retirement did not complete after lease release: " + closeFailure.get());
      require(handle.retirementStatus() == SessionHandle.RetirementStatus.RETIRED,
          "the exact CUDA handle did not retire");
      System.out.println("INSTALLED_GPU_LEASE_PASS " + gpu + " inputs=" + inputs.size()
          + " status=" + handle.retirementStatus());
    } finally {
      if (held != null) held.close();
      if (closer != null) closer.join(Duration.ofSeconds(10));
      handle.close();
    }
  }

  private static SessionAcquisitionRequest request() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(120));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
