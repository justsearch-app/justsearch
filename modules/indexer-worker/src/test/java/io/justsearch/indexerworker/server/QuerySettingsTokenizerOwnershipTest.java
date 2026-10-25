/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.reranker.RerankerAssembly;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** B-S4/6: rejected B and exact-source restoration have native ownership before wrappers exist. */
class QuerySettingsTokenizerOwnershipTest {
  @ParameterizedTest
  @ValueSource(longs = {2048L, 512L})
  void failedCandidateRetiresBeforeExactSourceRestoration(long free, @TempDir Path dir)
      throws Exception {
    var failed = new AtomicReference<InferenceSurface>();
    var restored = new AtomicReference<InferenceSurface>();
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, free);
        var composition = f.composition()) {
      var attempts = new AtomicInteger();
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(), any()))
          .thenAnswer(ignored -> {
            if (attempts.getAndIncrement() == 0) {
              var invalid = invalidShape(f.freshSurface(false));
              failed.set(invalid);
              return invalid;
            }
            verify(failed.get().reranker().orElseThrow().tokenizer()).close();
            var exact = f.freshSurface(false);
            restored.set(exact);
            return exact;
          });
      assertThrows(SettingsCommitOwner.Refused.class, f::prepare);
      verify(failed.get().reranker().orElseThrow().tokenizer()).close();
      if (free == 512L) f.assertRestored();
      else f.assertA();
      f.server.close();
      if (restored.get() != null) verify(restored.get().reranker().orElseThrow().tokenizer()).close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void successfulOrFailedRestorationKeepsExactlyOneCleanupOwner(boolean restorationFails,
      @TempDir Path dir) throws Exception {
    var restored = new AtomicReference<InferenceSurface>();
    try (var f = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 512L);
        var composition = f.composition()) {
      var attempts = new AtomicInteger();
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(), any()))
          .thenAnswer(ignored -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("candidate rejected");
            var exact = f.freshSurface(false);
            if (restorationFails) exact = invalidShape(exact);
            restored.set(exact);
            return exact;
          });
      assertThrows(SettingsCommitOwner.Refused.class, f::prepare);
      assertNotNull(restored.get());
      if (restorationFails) {
        verify(restored.get().reranker().orElseThrow().tokenizer()).close();
      } else {
        verify(restored.get().reranker().orElseThrow().tokenizer(), never()).close();
        f.assertRestored();
      }
      f.server.close();
      verify(restored.get().reranker().orElseThrow().tokenizer()).close();
    }
  }

  private static InferenceSurface invalidShape(InferenceSurface surface) {
    var original = surface.reranker().orElseThrow();
    return new InferenceSurface(Optional.empty(), Optional.empty(),
        Optional.of(new RerankerAssembly(original.sessions(), null, original.tokenizer())),
        Optional.empty(), Optional.empty(), Optional.empty(), surface.policies(), surface.handles(),
        surface.componentObservation());
  }
}
