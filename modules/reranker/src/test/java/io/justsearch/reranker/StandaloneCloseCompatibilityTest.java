/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.ort.SessionHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StandaloneCloseCompatibilityTest {
  static Stream<Arguments> unconfirmedRetirements() {
    return Stream.of("citation", "reranker").flatMap(role -> Stream.of(
        null, SessionHandle.RetirementStatus.ACTIVE, SessionHandle.RetirementStatus.RETIRING,
        SessionHandle.RetirementStatus.REFUSED).map(status -> Arguments.of(role, status)));
  }

  @ParameterizedTest
  @MethodSource("unconfirmedRetirements")
  void unconfirmedRetirementIsQuietAndRetainsTokenizerForRetry(
      String role, SessionHandle.RetirementStatus initial) throws Exception {
    var sessions = mock(SessionHandle.class);
    var status = new AtomicReference<>(initial);
    var allowRetirement = new AtomicBoolean();
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> {
      if (allowRetirement.get()) status.set(SessionHandle.RetirementStatus.RETIRED);
      return null;
    }).when(sessions).close();
    var tokenizer = mock(RerankerTokenizer.class);
    doAnswer(ignored -> {
      assertEquals(SessionHandle.RetirementStatus.RETIRED, status.get());
      return null;
    }).when(tokenizer).close();
    AutoCloseable wrapper = role.equals("citation")
        ? new CitationScorer(sessions, new RerankerShape(2, false), tokenizer)
        : new CrossEncoderReranker(sessions, new RerankerShape(2, false), tokenizer);

    assertDoesNotThrow(wrapper::close);
    verify(tokenizer, never()).close();
    allowRetirement.set(true);
    assertDoesNotThrow(wrapper::close);
    assertDoesNotThrow(wrapper::close);
    verify(tokenizer).close();
  }

  @Test
  void assemblyCloseStillReportsRefusalAndRetainsOwnership() {
    var sessions = mock(SessionHandle.class);
    var status = new AtomicReference<>(SessionHandle.RetirementStatus.REFUSED);
    when(sessions.retirementStatus()).thenAnswer(ignored -> status.get());
    var tokenizer = mock(RerankerTokenizer.class);
    var assembly = new RerankerAssembly(sessions, new RerankerShape(2, false), tokenizer);
    assertThrows(IllegalStateException.class, assembly::close);
    assertFalse(assembly.tokenizerClosed());
    verify(tokenizer, never()).close();
    status.set(SessionHandle.RetirementStatus.RETIRED);
    assertDoesNotThrow(assembly::close);
    assertDoesNotThrow(assembly::close);
    verify(tokenizer).close();
  }
}
