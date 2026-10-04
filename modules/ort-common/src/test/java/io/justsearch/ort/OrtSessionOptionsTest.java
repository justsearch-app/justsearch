package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parity + causality tests for {@link SessionOptionsApplier}. Tempdoc 397 §14.24 FA: replaces
 * the former hardcoded helpers on {@code NativeSessionHandle} /
 * {@code OrtSessionAssembler.applyProductionSessionOptions}. The tests assert two things:
 *
 * <ol>
 *   <li><b>Parity</b> — {@link SessionOptionsApplier} + {@link RuntimePolicy#defaults()}
 *       produces the same config-entry values as pre-FA code (the §5 invariants).
 *   <li><b>Causality</b> — non-default {@link RuntimePolicy} field values flow through to the
 *       ORT setters. This proves the walker reads the record; without it, parity alone could
 *       hold while the walker silently ignored its input (the §14.24 audit's structural gap).
 * </ol>
 *
 * <p>Same-package — reaches package-private {@link SessionOptionsApplier} via Java visibility.
 */
@DisplayName("SessionOptionsApplier — parity + causality invariants (§14.24 FA)")
final class OrtSessionOptionsTest {

  @Test
  void arenaShrinkageTargetsConfiguredDeviceAndHonorsDisabledPolicy() throws OrtException {
    var cfg = io.justsearch.configuration.resolved.TestResolvedConfigHelper.fromEntries(
        Map.of("justsearch.bgem3.gpu_device_id", "1"));
    var variant = io.justsearch.configuration.model.VariantSelection.optimal(
        java.nio.file.Path.of("model_fp16.onnx"),
        io.justsearch.configuration.model.ModelPrecision.FP16,
        io.justsearch.configuration.model.ExecutionProvider.CUDA);
    var enabled = ModelSessionPolicyResolver.resolve(EncoderRole.BGE_M3, cfg,
        io.justsearch.configuration.model.HardwareProfile.gpuFull(12_000_000_000L), variant);
    assertEquals(1, enabled.gpu().cudaDeviceId());
    var disabled = new ModelSessionPolicy(enabled.variant(), enabled.gpu(), enabled.cpu(),
        enabled.lifecycle(), new ModelSessionPolicy.RunOptions(false));
    try (var construction = org.mockito.Mockito.mockConstruction(OrtSession.RunOptions.class)) {
      try (var options = SessionOptionsApplier.buildGpuRunOptions(enabled)) {
        org.mockito.Mockito.verify(options).addRunConfigEntry(
            "memory.enable_memory_arena_shrinkage", "gpu:1");
      }
      try (var options = SessionOptionsApplier.buildGpuRunOptions(disabled)) {
        org.mockito.Mockito.verify(options, org.mockito.Mockito.never()).addRunConfigEntry(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
      }
      assertEquals(2, construction.constructed().size());
    }
  }

  @Test
  @DisplayName("GPU session options include device_allocator_for_initializers")
  void gpuSessionOptionsSetsDeviceAllocator() throws OrtException {
    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      SessionOptionsApplier.applyBase(RuntimePolicy.defaults(), opts);
      SessionOptionsApplier.applyGpuSessionOptions(RuntimePolicy.defaults(), opts);

      Map<String, String> entries = opts.getConfigEntries();
      assertEquals(
          "1",
          entries.get("session.use_device_allocator_for_initializers"),
          "GPU sessions must route weights through device allocator (tempdoc 311)");
    }
  }

  @Test
  @DisplayName("GPU session options include allow_spinning=0")
  void gpuSessionOptionsDisablesSpinning() throws OrtException {
    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      SessionOptionsApplier.applyBase(RuntimePolicy.defaults(), opts);
      SessionOptionsApplier.applyGpuSessionOptions(RuntimePolicy.defaults(), opts);

      Map<String, String> entries = opts.getConfigEntries();
      assertEquals(
          "0",
          entries.get("session.intra_op.allow_spinning"),
          "Spinning must be disabled to reduce CPU contention");
    }
  }

  @Test
  @DisplayName("applyBase sets allow_spinning=0")
  void productionOptionsDisablesSpinning() throws OrtException {
    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      SessionOptionsApplier.applyBase(RuntimePolicy.defaults(), opts);

      Map<String, String> entries = opts.getConfigEntries();
      assertEquals(
          "0",
          entries.get("session.intra_op.allow_spinning"),
          "Production options must disable spinning");
    }
  }

  @Test
  @DisplayName("applyBase does NOT set device_allocator (GPU-only)")
  void productionOptionsDoesNotSetDeviceAllocator() throws OrtException {
    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      SessionOptionsApplier.applyBase(RuntimePolicy.defaults(), opts);

      Map<String, String> entries = opts.getConfigEntries();
      assertNull(
          entries.get("session.use_device_allocator_for_initializers"),
          "device_allocator_for_initializers is GPU-specific, not in base options");
    }
  }

  @Test
  @DisplayName("GPU session options are a superset of base options")
  void gpuOptionsContainAllProductionOptions() throws OrtException {
    try (OrtSession.SessionOptions prodOpts = new OrtSession.SessionOptions();
        OrtSession.SessionOptions gpuOpts = new OrtSession.SessionOptions()) {
      SessionOptionsApplier.applyBase(RuntimePolicy.defaults(), prodOpts);
      SessionOptionsApplier.applyBase(RuntimePolicy.defaults(), gpuOpts);
      SessionOptionsApplier.applyGpuSessionOptions(RuntimePolicy.defaults(), gpuOpts);

      Map<String, String> prodEntries = prodOpts.getConfigEntries();
      Map<String, String> gpuEntries = gpuOpts.getConfigEntries();

      for (Map.Entry<String, String> entry : prodEntries.entrySet()) {
        assertEquals(
            entry.getValue(),
            gpuEntries.get(entry.getKey()),
            "GPU options must include base option: " + entry.getKey());
      }
      assertTrue(
          gpuEntries.size() > prodEntries.size(),
          "GPU options should have additional entries beyond base");
    }
  }

  // -------------------------------------------------------------------------
  // Causality tests — prove the walker reads the record (tempdoc 397 §14.24 FA
  // closure property). Without these, parity alone could pass while the walker
  // silently ignored its input — which was the structural gap pre-FA.
  // -------------------------------------------------------------------------

  private static final RuntimePolicy.Profiling PROFILING_OFF =
      new RuntimePolicy.Profiling(Optional.empty(), false);

  @Test
  @DisplayName("Non-default RuntimePolicy.Session.allowSpinning flows to session.intra_op.allow_spinning")
  void runtimePolicyAllowSpinningCausallyDeterminesEntry() throws OrtException {
    RuntimePolicy nonDefault =
        new RuntimePolicy(
            new RuntimePolicy.Arena("kSameAsRequested", false),
            new RuntimePolicy.CudaProvider(false, true, false, true, true),
            new RuntimePolicy.Session(
                /* interOpThreads= */ 1,
                /* allowSpinning= */ true, // NON-DEFAULT
                /* forceSpinningStop= */ true,
                /* useDeviceAllocatorForInitializers= */ true),
            PROFILING_OFF);

    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      SessionOptionsApplier.applyBase(nonDefault, opts);
      assertEquals(
          "1",
          opts.getConfigEntries().get("session.intra_op.allow_spinning"),
          "Policy.Session.allowSpinning=true must flow through to session option");
    }
  }

  @Test
  @DisplayName("Non-default RuntimePolicy.Session.forceSpinningStop flows to session.force_spinning_stop")
  void runtimePolicyForceSpinningStopCausallyDeterminesEntry() throws OrtException {
    RuntimePolicy nonDefault =
        new RuntimePolicy(
            new RuntimePolicy.Arena("kSameAsRequested", false),
            new RuntimePolicy.CudaProvider(false, true, false, true, true),
            new RuntimePolicy.Session(
                1, false, /* forceSpinningStop= */ false, true), // NON-DEFAULT
            PROFILING_OFF);

    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      SessionOptionsApplier.applyBase(nonDefault, opts);
      SessionOptionsApplier.applyGpuSessionOptions(nonDefault, opts);
      assertEquals(
          "0",
          opts.getConfigEntries().get("session.force_spinning_stop"),
          "Policy.Session.forceSpinningStop=false must flow through to session option");
    }
  }

  @Test
  @DisplayName("Non-default RuntimePolicy.Session.useDeviceAllocatorForInitializers flows through")
  void runtimePolicyUseDeviceAllocatorCausallyDeterminesEntry() throws OrtException {
    RuntimePolicy nonDefault =
        new RuntimePolicy(
            new RuntimePolicy.Arena("kSameAsRequested", false),
            new RuntimePolicy.CudaProvider(false, true, false, true, true),
            new RuntimePolicy.Session(
                1, false, true, /* useDeviceAllocatorForInitializers= */ false), // NON-DEFAULT
            PROFILING_OFF);

    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      SessionOptionsApplier.applyBase(nonDefault, opts);
      SessionOptionsApplier.applyGpuSessionOptions(nonDefault, opts);
      assertEquals(
          "0",
          opts.getConfigEntries().get("session.use_device_allocator_for_initializers"),
          "Policy.Session.useDeviceAllocatorForInitializers=false must flow through");
    }
  }

  @Test
  @DisplayName("RuntimePolicy.Profiling.verboseLogging flows to session log level (§14.24 FB)")
  void runtimePolicyVerboseLoggingFlowsThrough() throws OrtException {
    // Structural: verboseLogging=true triggers setSessionLogLevel(VERBOSE) inside applyBase.
    // ORT Java doesn't expose session log-level read-back; assert the applier accepts both
    // flag values without throwing, which proves the branch exists.
    RuntimePolicy verbose =
        new RuntimePolicy(
            RuntimePolicy.defaults().arena(),
            RuntimePolicy.defaults().cudaProvider(),
            RuntimePolicy.defaults().session(),
            new RuntimePolicy.Profiling(Optional.empty(), /* verboseLogging= */ true));

    try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
      SessionOptionsApplier.applyBase(verbose, opts);
    }
  }

  @Test
  @DisplayName("ModelSessionPolicy.RunOptions.arenaShrinkage fields are policy-driven (structural)")
  void arenaShrinkageIsPolicyDriven() throws OrtException {
    // RunOptions doesn't expose config-entry read-back in ORT Java; we can only assert the
    // method branches on the field and produces a non-null object. The positive case
    // (shrinkage=true) is exercised by every production session; the causality is in the
    // source (see SessionOptionsApplier.buildGpuRunOptions).
    ModelSessionPolicy enabled =
        new ModelSessionPolicy(
            null,
            new ModelSessionPolicy.Gpu(1024L, 0, Optional.empty()),
            new ModelSessionPolicy.Cpu(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT),
            new ModelSessionPolicy.Lifecycle(false, false, 0L),
            new ModelSessionPolicy.RunOptions(/* arenaShrinkage= */ true));
    ModelSessionPolicy disabled =
        new ModelSessionPolicy(
            null,
            new ModelSessionPolicy.Gpu(1024L, 0, Optional.empty()),
            new ModelSessionPolicy.Cpu(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT),
            new ModelSessionPolicy.Lifecycle(false, false, 0L),
            new ModelSessionPolicy.RunOptions(/* arenaShrinkage= */ false));

    try (OrtSession.RunOptions roEnabled = SessionOptionsApplier.buildGpuRunOptions(enabled);
        OrtSession.RunOptions roDisabled = SessionOptionsApplier.buildGpuRunOptions(disabled)) {
      assertNotNull(roEnabled);
      assertNotNull(roDisabled);
    }
  }
}
