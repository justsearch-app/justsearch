/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class BatchServiceFailuresProbeTest {
  static Stream<Throwable> probeFailures() {
    return Stream.of(new NoClassDefFoundError("missing shared mapping"),
        new AssertionError("broken health check"), new ThreadDeath(),
        new IllegalStateException("probe failed"));
  }

  @ParameterizedTest @MethodSource("probeFailures")
  void escapingNonVmProbeFailureKeepsLegacyPerFileHandling(Throwable fault) {
    var failures = new BatchServiceFailures();
    var systemic = new ArrayList<Boolean>();
    failures.begin();
    failures.failure(new IllegalStateException("failed document"), systemic::add);
    long delay = assertDoesNotThrow(() -> failures.prepareRetry(() -> {
      if (fault instanceof Error error) throw error;
      throw (RuntimeException) fault;
    }), "an unexpected health-check throwable must not escape classification");
    failures.finish(System.currentTimeMillis() + delay);
    assertEquals(0, delay);
    assertEquals(List.of(false), systemic, "retain the existing per-file outcome");
  }

  @Test void vmProbeFailureIsNeverSwallowed() {
    var failures = new BatchServiceFailures();
    failures.begin();
    failures.failure(new IllegalStateException("failed document"), ignored -> {});
    var fatal = new StackOverflowError("simulated fatal probe");
    assertSame(fatal, assertThrows(StackOverflowError.class,
        () -> failures.prepareRetry(() -> { throw fatal; })));
  }
}
