/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSurvivedException;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Terminal ownership over a {@link RecordingChildControl}: no OS process is started or ended, and
 * every stop is the control's, recorded with the Engine's request (Spec B §2.8).
 */
final class LlamaServerProcessOwnershipTest {
  private static final StopRequest QUIT =
      StopRequest.of(StopReason.QUIT, StopMode.NOW, "engine-shutdown");

  @Test
  void terminalFenceRefusesLaunchAndOwnershipHasNoAdoptionPath() {
    var control = new RecordingChildControl();
    var ownership = new LlamaServerProcessOwnership(control);
    ownership.stopTerminal(QUIT, Duration.ofMillis(25));
    assertThrows(IOException.class, ownership::requireLaunchAllowed);
    assertThrows(IOException.class, () -> ownership.launch(() -> {
      throw new AssertionError("terminal shutdown must fence every launch/relaunch");
    }));
    assertTrue(control.stops.isEmpty(), "nothing was owned, so nothing was stopped");
    // ADR-0051 decision 2: a child is owned only because this Engine launched it; there is no
    // way to take ownership of a predecessor's llama-server.
    assertThrows(NoSuchMethodException.class,
        () -> LlamaServerProcessOwnership.class.getDeclaredMethod("adoptManaged", ProcessHandle.class),
        "ownership must offer no adoption of a predecessor's llama-server");
  }

  @Test
  void anInflightLaunchCompletingAfterTheDeadlineIsStillStoppedWithTheTerminalRequest()
      throws Exception {
    var control = new RecordingChildControl();
    var ownership = new LlamaServerProcessOwnership(control);
    var child = control.child(ChildKind.LLAMA_SERVER);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var launch = new FutureTask<ChildHandle>(() -> ownership.launch(() -> {
      entered.countDown();
      try {
        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test launch was never released");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException(interrupted);
      }
      return child;
    }));
    Thread.ofPlatform().daemon(true).name("test-inflight-llama-launch").start(launch);
    try {
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
        assertThrows(IllegalStateException.class, () -> ownership.stopTerminal(QUIT, Duration.ofMillis(25)));
      });
      release.countDown();
      var rejected = assertThrows(ExecutionException.class, () -> launch.get(1, TimeUnit.SECONDS));
      assertInstanceOf(IOException.class, rejected.getCause());
      assertTrue(child.exit().toCompletableFuture().isDone(), "the late launch is ended");
      assertEquals(1, control.stops.size());
      assertSame(child, control.stops.getFirst().children().getFirst());
      assertSame(QUIT, control.stops.getFirst().request(), "stopped for the terminal reason");
    } finally {
      release.countDown();
      try { launch.get(1, TimeUnit.SECONDS); }
      catch (ExecutionException rejected) { assertInstanceOf(IOException.class, rejected.getCause()); }
    }
  }

  @Test
  void everyOwnedServerIsStoppedTogetherWithinOneSharedDeadline() throws Exception {
    var control = new RecordingChildControl();
    var ownership = new LlamaServerProcessOwnership(control);
    List<RecordingChildControl.Child> children = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      var child = control.child(ChildKind.LLAMA_SERVER);
      child.onStop = RecordingChildControl.OnStop.HANG;
      ownership.launch(() -> child);
      children.add(child);
    }
    assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
      assertThrows(IllegalStateException.class, () -> ownership.stopTerminal(QUIT, Duration.ofMillis(200)));
    });
    assertEquals(1, control.stops.size(), "one stopAll, sharing one grace window");
    var stop = control.stops.getFirst();
    assertTrue(stop.all());
    assertSame(QUIT, stop.request());
    assertEquals(8, stop.children().size());
    assertTrue(stop.children().containsAll(children));
  }

  @Test
  void aSurvivorReportedByTheControlFailsTheTerminalStopAtOnce() throws Exception {
    var control = new RecordingChildControl();
    var ownership = new LlamaServerProcessOwnership(control);
    var child = control.child(ChildKind.LLAMA_SERVER);
    child.onStop = RecordingChildControl.OnStop.SURVIVE;
    ownership.launch(() -> child);
    var failure = assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
        assertThrows(IllegalStateException.class, () -> ownership.stopTerminal(QUIT, Duration.ofSeconds(30))));
    assertInstanceOf(ChildSurvivedException.class, failure.getCause());
  }

  @Test
  void aServerThatExitedIsNoLongerOwned() throws Exception {
    var control = new RecordingChildControl();
    var ownership = new LlamaServerProcessOwnership(control);
    var child = control.child(ChildKind.LLAMA_SERVER);
    ownership.launch(() -> child);
    child.exitOnItsOwn(1);
    ownership.stopTerminal(QUIT, Duration.ofMillis(25));
    assertTrue(control.stops.isEmpty(), "an exited server is not stopped again");
  }
}
