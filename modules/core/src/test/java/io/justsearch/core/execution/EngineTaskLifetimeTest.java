/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class EngineTaskLifetimeTest {
  @Test void failedSecondRetainRollsBackTheFirstAndPreservesFailure() {
    var calls = new ArrayList<String>();
    var failure = new IllegalStateException("second refused");
    EngineTaskLifetime first = () -> { calls.add("retain first"); return () -> calls.add("release first"); };
    EngineTaskLifetime second = () -> { throw failure; };
    assertSame(failure, assertThrows(IllegalStateException.class, () -> first.and(second).retain()));
    assertEquals(java.util.List.of("retain first", "release first"), calls);
  }

  @Test void reverseReleaseAttemptsBothOwnersOnceEvenWhenCleanupFails() {
    var calls = new ArrayList<String>();
    var firstFailure = new IllegalStateException("first cleanup");
    var secondFailure = new IllegalStateException("second cleanup");
    EngineTaskLifetime first = () -> () -> { calls.add("first"); throw firstFailure; };
    EngineTaskLifetime second = () -> () -> { calls.add("second"); throw secondFailure; };
    Runnable release = first.and(second).retain();
    assertSame(secondFailure, assertThrows(IllegalStateException.class, release::run));
    assertArrayEquals(new Throwable[] {firstFailure}, secondFailure.getSuppressed());
    assertDoesNotThrow(release::run);
    assertEquals(java.util.List.of("second", "first"), calls);
  }
}
