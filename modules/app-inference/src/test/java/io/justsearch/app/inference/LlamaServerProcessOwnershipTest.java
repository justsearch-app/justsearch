/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** All processes/handles are fakes; these tests never start or terminate an OS process. */
final class LlamaServerProcessOwnershipTest {
  @Test
  void terminalFenceRefusesLaunchAndManagedAdoptionBeforeCreatingAChild() {
    var ownership = new LlamaServerProcessOwnership();
    ownership.stopTerminal(Duration.ofMillis(25));
    assertThrows(IOException.class, ownership::requireLaunchAllowed);
    assertThrows(IOException.class, () -> ownership.launch(() -> {
      throw new AssertionError("terminal shutdown must fence every launch/relaunch");
    }));
    assertThrows(IOException.class, () -> ownership.adoptManaged(mock(ProcessHandle.class)));
  }

  @Test
  void anInflightLaunchCompletingAfterTheDeadlineIsStillForcedToExit() throws Exception {
    var ownership = new LlamaServerProcessOwnership();
    var handle = mock(ProcessHandle.class);
    var exited = new CompletableFuture<ProcessHandle>();
    when(handle.isAlive()).thenAnswer(ignored -> !exited.isDone());
    when(handle.onExit()).thenReturn(exited);
    when(handle.destroyForcibly()).thenAnswer(ignored -> { exited.complete(handle); return true; });
    var process = mock(Process.class);
    when(process.toHandle()).thenReturn(handle);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var launch = new FutureTask<Process>(() -> ownership.launch(() -> {
      entered.countDown();
      try {
        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test launch was never released");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException(interrupted);
      }
      return process;
    }));
    Thread.ofPlatform().daemon(true).name("test-inflight-llama-launch").start(launch);
    try {
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
        assertThrows(IllegalStateException.class, () -> ownership.stopTerminal(Duration.ofMillis(25)));
      });
      release.countDown();
      var rejected = assertThrows(ExecutionException.class, () -> launch.get(1, TimeUnit.SECONDS));
      assertInstanceOf(IOException.class, rejected.getCause());
      assertFalse(handle.isAlive());
      verify(handle, atLeastOnce()).destroyForcibly();
    } finally {
      release.countDown();
      try { launch.get(1, TimeUnit.SECONDS); }
      catch (ExecutionException rejected) { assertInstanceOf(IOException.class, rejected.getCause()); }
    }
  }

  @Test
  void everySurvivorIsForcedWithinOneSharedDeadline() {
    var ownership = new LlamaServerProcessOwnership();
    var handles = new java.util.ArrayList<ProcessHandle>();
    for (int index = 0; index < 8; index++) {
      var handle = mock(ProcessHandle.class);
      when(handle.isAlive()).thenReturn(true);
      when(handle.onExit()).thenReturn(new CompletableFuture<>());
      ownership.capture(handle);
      handles.add(handle);
    }
    assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
      assertThrows(IllegalStateException.class, () -> ownership.stopTerminal(Duration.ofMillis(200)));
    });
    for (var handle : handles) {
      verify(handle).destroy();
      verify(handle).destroyForcibly();
    }
  }

  @Test
  void failedExitObservationStillForcesTheOwnedProcess() {
    var ownership = new LlamaServerProcessOwnership();
    var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
    var handle = mock(ProcessHandle.class);
    when(handle.isAlive()).thenAnswer(ignored -> alive.get());
    when(handle.onExit()).thenReturn(CompletableFuture.failedFuture(new IOException("exit query failed")));
    when(handle.destroyForcibly()).thenAnswer(ignored -> { alive.set(false); return true; });
    ownership.capture(handle);
    ownership.stopTerminal(Duration.ofMillis(25));
    assertFalse(handle.isAlive());
    verify(handle).destroyForcibly();
  }
}
