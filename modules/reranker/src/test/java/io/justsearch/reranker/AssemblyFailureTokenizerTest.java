/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import ai.onnxruntime.OrtException;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AssemblyFailureTokenizerTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedProbeReleasesRerankerOrCitationTokenizer(boolean citation, @TempDir Path dir)
      throws Exception {
    var failure = new OrtException("injected name probe failure");
    var sessions = mock(SessionHandle.class);
    var status = new java.util.concurrent.atomic.AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> { status.set(SessionHandle.RetirementStatus.RETIRED); return null; })
        .when(sessions).close();
    try (var tokenizers = mockConstruction(RerankerTokenizer.class);
        var probe = mockStatic(OrtSessionAssembler.class)) {
      probe.when(() -> OrtSessionAssembler.probeModelNames(any(), any())).thenThrow(failure);
      var thrown = assertThrows(Exception.class, () -> {
        if (citation) CitationScorer.buildAssembly(sessions, dir.resolve("tokenizer.json"), 512);
        else CrossEncoderReranker.buildAssembly(sessions, dir.resolve("tokenizer.json"), 512);
      });
      assertSame(failure, thrown.getCause() == null ? thrown : thrown.getCause());
      verify(tokenizers.constructed().getFirst(), never()).close();
      sessions.close();
      if (thrown instanceof AutoCloseable owner) {
        owner.close();
        owner.close();
      }
      verify(tokenizers.constructed().getFirst()).close();
    }
  }
  @org.junit.jupiter.api.Test
  void failureBeforeAnySessionExistsClosesTokenizerDirectly(@TempDir Path dir) throws Exception {
    try (var tokenizers = mockConstruction(RerankerTokenizer.class)) {
      assertThrows(NullPointerException.class,
          () -> CrossEncoderReranker.buildAssembly(null, dir.resolve("tokenizer.json"), 512));
      verify(tokenizers.constructed().getFirst()).close();
    }
  }

  /** B-P3/10: the standalone benchmark constructor retains its tokenizer through handoffs. */
  @org.junit.jupiter.api.Test
  void repeatedGpuHandoffsKeepStandaloneRerankerTokenizerUntilClose() {
    var sessions = mock(SessionHandle.class);
    var status = new java.util.concurrent.atomic.AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> { status.set(SessionHandle.RetirementStatus.RETIRED); return null; })
        .when(sessions).close();
    var tokenizer = mock(RerankerTokenizer.class);
    var reranker = new CrossEncoderReranker(sessions, new RerankerShape(512, false), tokenizer);
    for (int handoff = 0; handoff < 4; handoff++) {
      reranker.releaseGpuSession();
      verify(tokenizer, never()).close();
    }
    verify(sessions, org.mockito.Mockito.times(4)).releaseGpu();
    reranker.close();
    verify(tokenizer).close();
  }

}
