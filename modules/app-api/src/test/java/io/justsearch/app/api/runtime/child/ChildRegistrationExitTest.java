/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Registration sees identity loss before the original Process publishes its exit callback. */
@Timeout(10)
final class ChildRegistrationExitTest {
  @TempDir Path dataDir;
  @Test
  void lostStartInstantReturnsTheOriginalExitAndOutput() throws Exception {
    assertEarlyExit(new ControlledProcess(Loss.START));
  }

  @Test
  void lostExecutableReturnsTheOriginalExitAndOutput() throws Exception {
    assertEarlyExit(new ControlledProcess(Loss.EXECUTABLE));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void lostLinuxIdentityReturnsTheOriginalExitAndOutput() throws Exception {
    assertEarlyExit(new ControlledProcess(Loss.LINUX_IDENTITY));
  }

  @Test
  void kernelExitBeforeJdkReapingWaitsOnTheOriginalProcess() throws Exception {
    ControlledProcess process = new ControlledProcess(Loss.START);
    process.reaperLag = true;
    assertEarlyExit(process);
    assertEquals(List.of(DefaultChildControl.REGISTRATION_EXIT_WAIT), process.waits);
  }

  @Test
  void exitCallbackDuringConfirmationPublishesOnlyOnce() throws Exception {
    ControlledProcess process = new ControlledProcess(Loss.START);
    process.reaperLag = true;
    process.publishDuringWait = true;
    assertEarlyExit(process);
  }

  @Test
  void interruptionIsPreservedWhenTheOriginalProcessConfirmsExit() throws Exception {
    ControlledProcess process = new ControlledProcess(Loss.START);
    process.reaperLag = true;
    process.interruptWait = true;
    try {
      assertEarlyExit(process);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void liveChildWithUnreadableIdentityIsStillRefusedAndRolledBack() throws Exception {
    ControlledProcess process = new ControlledProcess(Loss.START);
    process.exitDuringCapture = false;
    try (Fixture fixture = new Fixture(process)) {
      ChildRegistrationRefusedException failure = assertThrows(ChildRegistrationRefusedException.class,
          () -> fixture.control.start(ChildControlHarness.spec("sleep")));
      assertEquals("started child has no process start instant", failure.getCause().getMessage());
      assertEquals(1, process.terminations);
      assertTrue(fixture.registry.registered.isEmpty());
      assertEquals(Optional.of(StopReason.ROLLBACK), fixture.control.recentExits(1).getFirst().reason());
    }
  }

  @Test
  void registryWriteFailureStillRefusesEvenWhenTheChildHasExited() throws Exception {
    ControlledProcess process = new ControlledProcess(Loss.NONE);
    try (Fixture fixture = new Fixture(process)) {
      fixture.registry.duringRegister = entry -> {
        process.exited = true;
        throw new IOException("manifest write refused");
      };
      ChildRegistrationRefusedException failure = assertThrows(ChildRegistrationRefusedException.class,
          () -> fixture.control.start(ChildControlHarness.spec("sleep")));
      assertEquals("manifest write refused", failure.getCause().getMessage());
      assertTrue(process.waits.stream().noneMatch(DefaultChildControl.REGISTRATION_EXIT_WAIT::equals));
    }
  }

  @Test
  void exitRecordedAfterRegistrationRemovesTheEntry() throws Exception {
    ControlledProcess process = new ControlledProcess(Loss.NONE);
    try (Fixture fixture = new Fixture(process)) {
      fixture.registry.duringRegister = entry -> process.exited = true;
      ChildHandle child = fixture.control.start(ChildControlHarness.spec("sleep"));
      assertEquals(1, fixture.registry.snapshot().size());
      process.callback.complete(process);
      ChildControlHarness.await(child.exit());
      assertTrue(fixture.registry.snapshot().isEmpty());
      assertEquals(List.of(fixture.registry.registered.getFirst().id()), fixture.registry.removed);
    }
  }

  @Test
  void liveRegistrationKeepsAllIdentityAndConfigurationFields() throws Exception {
    try (ChildControlHarness harness = new ChildControlHarness(dataDir)) {
      ChildSpec spec = ChildControlHarness.spec("sleep");
      String model = dataDir.resolve("models/../model.gguf").toString();
      ChildHandle child = harness.control().start(spec,
          new ChildControl.ManifestEntry("test-endpoint", model, "d".repeat(64)));
      ManagedChild entry = harness.registry.registered.getFirst();
      Process original = harness.terminator.processes.getFirst();
      assertEquals(ManagedChild.Kind.EXTRACTION, entry.kind());
      assertEquals(original.pid(), entry.pid());
      assertEquals(original.info().startInstant().orElseThrow().toString(), entry.startedAt());
      assertEquals(ManagedChild.normalizePath(Path.of(original.info().command().orElseThrow())), entry.executable());
      assertEquals(ManagedChild.normalizePath(Path.of(model)), entry.modelPath());
      assertEquals("test-endpoint", entry.endpoint());
      assertEquals("d".repeat(64), entry.declaredConfigHash());
      assertEquals(DefaultChildControl.argvHash(spec.argv()), entry.realizedArgvHash());
      if (io.justsearch.configuration.PlatformPaths.isLinux()) {
        assertEquals(LinuxProcessIdentity.read(original.pid()).orElseThrow(), entry.processIdentity());
      }
      ChildControlHarness.await(harness.control().stop(child,
          StopRequest.of(StopReason.QUIT, StopMode.NOW)));
      assertTrue(harness.registry.snapshot().isEmpty());
      harness.assertLedgerConforms();
    }
  }

  @Test
  void captureFailureWaitsForTheWatchersSinglePublication() throws Exception {
    assertPendingPublication(false);
  }

  @Test
  void completedRegistryWriteWaitsForTheWatchersSinglePublication() throws Exception {
    assertPendingPublication(true);
  }

  private static void assertPendingPublication(boolean registered) throws Exception {
    var publishing = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    ControlledProcess process = new ControlledProcess(registered ? Loss.NONE : Loss.START);
    try (Fixture fixture = new Fixture(process);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      fixture.beforeExit = () -> {
        publishing.countDown();
        awaitLatch(release);
      };
      Runnable exit = () -> {
        process.exited = true;
        Thread.ofPlatform().daemon().start(() -> process.callback.complete(process));
        awaitLatch(publishing);
      };
      if (registered) fixture.registry.duringRegister = entry -> exit.run();
      else process.duringCapture = exit;
      var result = executor.submit(() -> fixture.control.start(ChildControlHarness.spec("sleep")));
      assertTrue(publishing.await(5, TimeUnit.SECONDS));
      try {
        assertThrows(java.util.concurrent.TimeoutException.class,
            () -> result.get(200, TimeUnit.MILLISECONDS), "start must wait for the exit outcome");
      } finally {
        release.countDown();
      }
      ChildHandle child = result.get(5, TimeUnit.SECONDS);
      ExitOutcome outcome = child.exit().toCompletableFuture().getNow(null);
      assertEquals(OptionalInt.of(23), java.util.Objects.requireNonNull(outcome).code());
      assertEquals(ChildHandle.State.EXITED, child.state());
      assertTrue(fixture.registry.snapshot().isEmpty());
      assertEquals(List.of(outcome), fixture.control.recentExits(32));
      assertEquals(1, fixture.exitFields.size());
      assertEquals(1, fixture.types.stream().filter(LedgerRecordType.CHILD_EXIT::equals).count());
    } finally {
      release.countDown();
    }
  }

  private static void awaitLatch(java.util.concurrent.CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("exit publication timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static void assertEarlyExit(ControlledProcess process) throws Exception {
    try (Fixture fixture = new Fixture(process)) {
      ChildHandle child = assertDoesNotThrow(() -> fixture.control.start(ChildControlHarness.spec("sleep")));
      ExitOutcome outcome = ChildControlHarness.await(child.exit());
      assertEquals(ChildHandle.State.EXITED, child.state());
      assertEquals(OptionalInt.of(23), outcome.code());
      assertEquals(ExitClass.UNEXPECTED, outcome.exitClass());
      assertEquals(Optional.empty(), outcome.reason());
      assertEquals(Optional.empty(), outcome.mode());
      assertEquals(TerminateResult.NOT_CALLED, outcome.terminateResult());
      assertEquals("fixture output\n", new String(child.stdout().readAllBytes(), StandardCharsets.UTF_8));
      assertEquals("fixture diagnostic\n", new String(child.stderr().readAllBytes(), StandardCharsets.UTF_8));
      assertTrue(fixture.registry.registered.isEmpty());
      assertTrue(fixture.control.list().isEmpty());
      assertFalse(outcome.forced());
      process.callback.complete(process); // the late watcher must not publish a second exit
      assertEquals(outcome, ChildControlHarness.await(child.exit()));
      assertEquals(List.of(outcome), fixture.control.recentExits(32));
      assertEquals(List.of(LedgerRecordType.CHILD_SPAWN, LedgerRecordType.CHILD_START,
          LedgerRecordType.CHILD_EXIT), fixture.types);
      assertEquals(OptionalInt.of(23), fixture.exitFields.getFirst().get("code"));
      assertEquals(null, fixture.exitFields.getFirst().get("intentId"));
      assertEquals(0, process.terminations);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final ChildControlHarness.RecordingRegistry registry = new ChildControlHarness.RecordingRegistry();
    final List<LedgerRecordType> types = new ArrayList<>();
    final List<Map<String, ?>> exitFields = new ArrayList<>();
    final DefaultChildControl control;
    Runnable beforeExit = () -> {};

    Fixture(ControlledProcess process) {
      LedgerSink ledger = (type, fields) -> {
        types.add(type);
        if (type == LedgerRecordType.CHILD_EXIT) {
          exitFields.add(fields);
          beforeExit.run();
        }
        return true;
      };
      control = new DefaultChildControl(ledger, new PosixProcessTerminator(137), VramProbe.none(),
          Clock.systemUTC(), DefaultChildControl.Sleeper.REAL, registry,
          new EngineIdentity(ProcessHandle.current().pid(), Optional.empty(), "registration-test"),
          Thread.ofPlatform().daemon().factory(), DefaultChildControl.JobMembership.NONE,
          (builder, kind) -> process);
    }

    @Override
    public void close() {
      control.close();
    }
  }

  private enum Loss { NONE, START, EXECUTABLE, LINUX_IDENTITY }

  /** No real process is signalled. The callback and JDK exit status have separate release points. */
  private static final class ControlledProcess extends Process {
    final Loss loss;
    final CompletableFuture<Process> callback = new CompletableFuture<>();
    final List<Duration> waits = new ArrayList<>();
    final ProcessHandle.Info initial = ProcessHandle.current().info();
    boolean exitDuringCapture = true;
    boolean reaperLag;
    boolean publishDuringWait;
    boolean interruptWait;
    volatile boolean exited;
    Runnable duringCapture = () -> {};
    int infoReads;
    int terminations;

    ControlledProcess(Loss loss) {
      this.loss = loss;
    }

    @Override public ProcessHandle.Info info() {
      if (++infoReads < 3 || loss == Loss.NONE) return initial;
      if (exitDuringCapture && !reaperLag) exited = true;
      duringCapture.run();
      return new ProcessHandle.Info() {
        @Override public Optional<Instant> startInstant() {
          return loss == Loss.START ? Optional.empty() : initial.startInstant();
        }
        @Override public Optional<String> command() {
          return loss == Loss.EXECUTABLE ? Optional.empty() : initial.command();
        }
        @Override public Optional<String> commandLine() { return Optional.empty(); }
        @Override public Optional<String[]> arguments() { return Optional.empty(); }
        @Override public Optional<Duration> totalCpuDuration() { return Optional.empty(); }
        @Override public Optional<String> user() { return Optional.empty(); }
      };
    }

    @Override public long pid() {
      return loss == Loss.LINUX_IDENTITY ? Long.MAX_VALUE : ProcessHandle.current().pid();
    }
    @Override public ProcessHandle toHandle() { throw new UnsupportedOperationException("no PID lookup"); }
    @Override public CompletableFuture<Process> onExit() { return callback; }
    @Override public boolean isAlive() { return !exited; }
    @Override public int exitValue() {
      if (!exited) throw new IllegalThreadStateException("not reaped yet");
      return 23;
    }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      waits.add(Duration.ofNanos(unit.toNanos(timeout)));
      if (exitDuringCapture && infoReads >= 3) exited = true;
      if (interruptWait) throw new InterruptedException("reaper wait interrupted");
      if (exited && publishDuringWait) callback.complete(this);
      return exited;
    }
    @Override public int waitFor() { return exitValue(); }
    @Override public void destroy() { destroyForcibly(); }
    @Override public Process destroyForcibly() {
      terminations++;
      exited = true;
      return this;
    }
    @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    @Override public InputStream getInputStream() {
      return new ByteArrayInputStream("fixture output\n".getBytes(StandardCharsets.UTF_8));
    }
    @Override public InputStream getErrorStream() {
      return new ByteArrayInputStream("fixture diagnostic\n".getBytes(StandardCharsets.UTF_8));
    }
  }
}
