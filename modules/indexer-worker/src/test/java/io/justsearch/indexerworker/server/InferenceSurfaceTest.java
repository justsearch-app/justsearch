package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArbiter;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.PolicySnapshot;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionHandle;
import io.justsearch.ort.SessionHandle.Lease;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InferenceSurface} (tempdoc 397 §14.28 U7). Covers the record's
 * {@link InferenceSurface#close} iteration + Optional semantics + policies-map invariants.
 * Does not require real ORT sessions — uses a counting {@link SessionHandle} stub that tracks
 * close invocations.
 */
@DisplayName("InferenceSurface (§14.28 U7)")
class InferenceSurfaceTest {

  /** Counting stub — tracks close invocations; can be configured to throw on close. */
  private static final class CountingHandle implements SessionHandle {
    final AtomicInteger closeCount = new AtomicInteger();
    final boolean throwOnClose;

    CountingHandle() {
      this(false);
    }

    CountingHandle(boolean throwOnClose) {
      this.throwOnClose = throwOnClose;
    }

    @Override
    public Lease acquire() {
      throw new UnsupportedOperationException("not used in tests");
    }

    @Override
    public Lease acquireCpu() {
      throw new UnsupportedOperationException("not used in tests");
    }

    @Override
    public ai.onnxruntime.OrtEnvironment environment() {
      throw new UnsupportedOperationException("not used in tests");
    }

    @Override
    public boolean isGpuAvailable() {
      return false;
    }

    @Override
    public OrtCudaStatus status() {
      return OrtCudaStatus.notConfigured();
    }

    @Override
    public void releaseGpu() {}

    @Override
    public void reportCpuSessionFailure(io.justsearch.ort.telemetry.CpuRecreateCause cause) {}

    @Override
    public void setLifecycleCallback(io.justsearch.ort.GpuLifecycleCallback callback) {}

    @Override
    public void setOrtRunRecorder(io.justsearch.ort.OrtRunRecorder recorder) {}

    @Override
    public void close() {
      closeCount.incrementAndGet();
      if (throwOnClose) {
        throw new RuntimeException("simulated close failure");
      }
    }
  }

  private static PolicySnapshot emptySnapshot() {
    return new PolicySnapshot(RuntimePolicy.defaults(), new TreeMap<>());
  }

  @Test
  @DisplayName("close invokes each wired handle")
  void closeInvokesEachHandle() {
    CountingHandle h1 = new CountingHandle();
    CountingHandle h2 = new CountingHandle();
    CountingHandle h3 = new CountingHandle();
    InferenceSurface surface =
        new InferenceSurface(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            emptySnapshot(),
            List.of(h1, h2, h3));

    surface.close();

    assertEquals(1, h1.closeCount.get());
    assertEquals(1, h2.closeCount.get());
    assertEquals(1, h3.closeCount.get());
  }

  @Test
  @DisplayName("close swallows per-handle exceptions — shutdown continues across failures")
  void closeSwallowsPerHandleExceptions() {
    CountingHandle h1 = new CountingHandle();
    CountingHandle h2 = new CountingHandle(/* throwOnClose= */ true);
    CountingHandle h3 = new CountingHandle();
    InferenceSurface surface =
        new InferenceSurface(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            emptySnapshot(),
            List.of(h1, h2, h3));

    assertDoesNotThrow(surface::close);
    // All three still invoked — close iteration doesn't abort on a single failure.
    assertEquals(1, h1.closeCount.get());
    assertEquals(1, h2.closeCount.get(), "throwing handle's close was still invoked");
    assertEquals(1, h3.closeCount.get(), "post-exception handle was still closed");
  }

  @Test
  @DisplayName("close is idempotent")
  void closeIsIdempotent() {
    CountingHandle h1 = new CountingHandle();
    InferenceSurface surface =
        new InferenceSurface(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            emptySnapshot(),
            List.of(h1));

    surface.close();
    assertDoesNotThrow(surface::close); // second close doesn't throw
    // Each close() call iterates the handles list — handle.close() is called twice total.
    // This is fine because SessionHandle.close() is contractually idempotent itself.
    assertEquals(2, h1.closeCount.get());
  }

  @Test
  @DisplayName("empty surface — zero handles, zero policies, close is a no-op")
  void emptySurfaceIsConstructibleAndCloseIsNoop() {
    InferenceSurface surface =
        new InferenceSurface(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            emptySnapshot(),
            List.of());

    assertTrue(surface.embedding().isEmpty());
    assertTrue(surface.ner().isEmpty());
    assertTrue(surface.reranker().isEmpty());
    assertTrue(surface.citation().isEmpty());
    assertTrue(surface.splade().isEmpty());
    assertTrue(surface.bgeM3().isEmpty());
    assertTrue(surface.handles().isEmpty());
    assertTrue(surface.policies().models().isEmpty());

    assertDoesNotThrow(surface::close);
  }

  @Test
  @DisplayName("policies map is preserved via PolicySnapshot — TreeMap ordering guaranteed")
  void policiesMapOrderingIsPreserved() {
    // PolicySnapshot enforces TreeMap internally (§14.26 §0-era invariant). This test pins
    // the invariant: a surface built with specific roles produces a PolicySnapshot whose map
    // iterates in EncoderRole declaration order (EMBEDDING first, BGE_M3, SPLADE, NER,
    // RERANKER, CITATION).
    TreeMap<EncoderRole, ModelSessionPolicy> models = new TreeMap<>();
    // Synthesise two entries out-of-order; assert ordering.
    // Using forFallback with no GPU for test simplicity — the content doesn't matter, the
    // key order does.
    ModelSessionPolicy p1 =
        ModelSessionPolicy.forFallback(null, null, false, false, 60_000L);
    models.put(EncoderRole.SPLADE, p1);
    models.put(EncoderRole.EMBEDDING, p1);
    PolicySnapshot snapshot = new PolicySnapshot(RuntimePolicy.defaults(), models);
    InferenceSurface surface =
        new InferenceSurface(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            snapshot,
            List.of());

    List<EncoderRole> order = new ArrayList<>(surface.policies().models().keySet());
    // EMBEDDING comes before SPLADE in the enum declaration.
    assertEquals(List.of(EncoderRole.EMBEDDING, EncoderRole.SPLADE), order);
  }

  @Test
  @DisplayName("legacy constructor carries an explicitly unknown component observation")
  void legacyConstructorObservationIsUnknown() {
    InferenceSurface surface =
        new InferenceSurface(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            emptySnapshot(),
            List.of());

    assertTrue(surface.componentObservation().configurationDigest().isEmpty());
    assertFalse(surface.componentObservation().compositionSatisfied());
  }

  @Test
  @DisplayName("a composed lexical-only surface is unavailable")
  void noRequestedRolesAreUnavailable() {
    InferenceSurface.ComponentObservation observation =
        InferenceSurface.ComponentObservation.composed("digest", Set.of(), Set.of());

    assertTrue(observation.configurationDigest().isPresent());
    assertFalse(observation.hasRequestedRoles());
    assertFalse(observation.compositionSatisfied());
  }

  @Test
  @DisplayName("requested roles missing from the surface prevent readiness")
  void requestedMissingRoleIsUnavailable() {
    InferenceSurface.ComponentObservation observation =
        InferenceSurface.ComponentObservation.composed(
            "digest", Set.of(EncoderRole.EMBEDDING, EncoderRole.NER), Set.of(EncoderRole.NER));

    assertEquals(Set.of(EncoderRole.EMBEDDING), observation.missingRoles());
    assertFalse(observation.compositionSatisfied());
  }

  @Test
  @DisplayName("a successful requested subset satisfies composition")
  void successfulRequestedSubsetSatisfiesComposition() {
    InferenceSurface.ComponentObservation observation =
        InferenceSurface.ComponentObservation.composed(
            "digest",
            Set.of(EncoderRole.EMBEDDING, EncoderRole.CITATION),
            Set.of(EncoderRole.EMBEDDING, EncoderRole.CITATION));

    assertTrue(observation.missingRoles().isEmpty());
    assertTrue(observation.compositionSatisfied());
  }

  @Test
  @DisplayName("BGE selection remains missing when a SPLADE fallback is present")
  void bgeFailureRemainsVisibleThroughSpladeFallback() {
    InferenceSurface.ComponentObservation observation =
        InferenceSurface.ComponentObservation.composed(
            "digest",
            Set.of(EncoderRole.BGE_M3, EncoderRole.SPLADE),
            Set.of(EncoderRole.SPLADE));

    assertEquals(Set.of(EncoderRole.BGE_M3), observation.missingRoles());
    assertFalse(observation.compositionSatisfied());
  }
}
