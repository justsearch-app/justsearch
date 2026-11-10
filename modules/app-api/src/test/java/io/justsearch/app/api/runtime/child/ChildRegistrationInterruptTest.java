/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.api.runtime.ledger.LedgerWriterId;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real process exits and disk writes at each interrupted registration wait boundary. */
@Timeout(20)
final class ChildRegistrationInterruptTest {
  @TempDir Path directory;

  enum Timing { BEFORE, DURING, AFTER }

  @ParameterizedTest
  @EnumSource(Timing.class)
  void interruptedConfirmationPersistsTheOriginalExit(Timing timing) throws Exception {
    try (Fixture fixture = new Fixture(timing, true, false);
        var executor = Executors.newSingleThreadExecutor()) {
      StartResult result = executor.submit(fixture::start).get(10, TimeUnit.SECONDS);
      assertNull(result.failure());
      assertTrue(result.interrupted(), "caller retains its interrupt");
      fixture.assertExit(result.child());
    }
  }

  @ParameterizedTest
  @EnumSource(Timing.class)
  void interruptedConfirmationStillRollsBackALiveChild(Timing timing) throws Exception {
    try (Fixture fixture = new Fixture(timing, false, false);
        var executor = Executors.newSingleThreadExecutor()) {
      StartResult result = executor.submit(fixture::start).get(10, TimeUnit.SECONDS);
      assertInstanceOf(ChildRegistrationRefusedException.class, result.failure());
      assertTrue(result.interrupted(), "refused caller retains its interrupt");
      assertTrue(fixture.process.waitFor(5, TimeUnit.SECONDS));
      fixture.callback.complete(fixture.process);
      ExitOutcome outcome = ((ChildRegistrationRefusedException) result.failure()).rolledBack()
          .exit().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertFalse(fixture.process.isAlive(), "rollback ends the owned real process");
      assertEquals(Optional.of(StopReason.ROLLBACK), outcome.reason());
      assertTrue(outcome.ledgerDurable());
      assertEquals(1, fixture.exitRecords());
      assertTrue(fixture.registry.snapshot().isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void interruptedPublicationWaitRemovesThePersistedManifest(boolean registeredExit) throws Exception {
    try (Fixture fixture = new Fixture(null, true, true);
        var executor = Executors.newSingleThreadExecutor()) {
      fixture.captureFailure = !registeredExit;
      var pending = executor.submit(fixture::start);
      assertTrue(fixture.publishing.await(5, TimeUnit.SECONDS));
      if (registeredExit) assertTrue(fixture.registered.await(5, TimeUnit.SECONDS));
      try {
        assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));
        fixture.starter.get().interrupt();
        assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));
      } finally {
        fixture.release.countDown();
      }
      StartResult result = pending.get(5, TimeUnit.SECONDS);
      assertNull(result.failure());
      assertTrue(result.interrupted());
      fixture.assertExit(result.child());
      assertTrue(fixture.registry.snapshot().isEmpty());
      if (registeredExit) {
        assertEquals("", Files.readString(fixture.manifest), "reload finds no stale manifest entry");
      } else {
        assertFalse(Files.exists(fixture.manifest), "identity loss never creates a manifest entry");
      }
    }
  }

  private record StartResult(ChildHandle child, Exception failure, boolean interrupted) {}

  private final class Fixture implements AutoCloseable {
    final Path ledger = LedgerFamily.directory(directory);
    final Path manifest = directory.resolve("children.txt");
    final CountDownLatch publishing = new CountDownLatch(1);
    final CountDownLatch registered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicReference<Thread> starter = new AtomicReference<>();
    final CompletableFuture<Process> callback = new CompletableFuture<>();
    final ExitLedgerWriter writer = ExitLedgerWriter.rotating(ledger, LedgerFamily.CHILDREN,
        LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, Instant.now()), Clock.systemUTC());
    final ManagedChildRegistry registry = new ManagedChildRegistry() {
      private List<ManagedChild> entries = List.of();
      @Override public synchronized List<ManagedChild> snapshot() { return entries; }
      @Override public synchronized void register(ManagedChild child) throws IOException {
        persist(child.id());
        entries = List.of(child);
      }
      @Override public synchronized void remove(String id) throws IOException {
        persist("");
        entries = List.of();
      }
      private void persist(String value) throws IOException {
        try (FileChannel channel = FileChannel.open(manifest, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
          ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
          while (bytes.hasRemaining()) channel.write(bytes);
          channel.force(true);
        }
      }
    };
    final DefaultChildControl control;
    Process process;
    boolean captureFailure;

    Fixture(Timing timing, boolean exits, boolean publication) {
      LedgerSink sink = writer::append;
      ProcessTerminator delegate = ProcessTerminator.forCurrentOs();
      ProcessTerminator terminator = new ProcessTerminator() {
        @Override public int ourCode() { return delegate.ourCode(); }
        @Override public boolean codeDistinctive() { return delegate.codeDistinctive(); }
        @Override public Optional<String> creationFileTime(long pid) { return delegate.creationFileTime(pid); }
        @Override public OsProcess open(Process child) throws IOException {
          OsProcess handle = delegate.open(child);
          return new OsProcess() {
            @Override public Optional<String> creationFileTime() { return handle.creationFileTime(); }
            @Override public Optional<LinuxProcessIdentity> processIdentity() { return handle.processIdentity(); }
            @Override public TerminateResult terminate(int code) { return handle.terminate(code); }
            @Override public boolean awaitExit(Duration max) throws InterruptedException { return handle.awaitExit(max); }
            @Override public OptionalInt exitCode() { return handle.exitCode(); }
            @Override public Optional<Instant> exitedAt() { return handle.exitedAt(); }
            @Override public void close() {
              if (publication) {
                // The watcher already captured a null registry id, before register can return.
                publishing.countDown();
                await(release);
              }
              handle.close();
            }
          };
        }
      };
      ManagedChildRegistry observedRegistry = new ManagedChildRegistry() {
        @Override public List<ManagedChild> snapshot() { return registry.snapshot(); }
        @Override public void register(ManagedChild child) throws IOException {
          registry.register(child);
          if (publication) {
            finishProcess();
            Thread.ofPlatform().daemon().start(() -> callback.complete(process));
            await(publishing);
            registered.countDown();
          }
        }
        @Override public void remove(String id) throws IOException { registry.remove(id); }
      };
      control = new DefaultChildControl(sink, terminator, VramProbe.none(),
          Clock.systemUTC(), DefaultChildControl.Sleeper.REAL, observedRegistry,
          new EngineIdentity(ProcessHandle.current().pid(), Optional.empty(), "interrupt-test"),
          Thread.ofPlatform().daemon().factory(), DefaultChildControl.JobMembership.NONE,
          (builder, kind) -> {
            builder.command(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ExitChild.class.getName()));
            process = builder.start();
            if (!exits) process.onExit().thenRun(() -> callback.complete(process));
            return new Process() {
              int reads;
              int aliveReads;
              int waits;
              @Override public ProcessHandle.Info info() {
                ProcessHandle.Info info = process.info();
                if (++reads == 3 && (!publication || captureFailure)) {
                  if (exits) {
                    try {
                      finishProcess();
                      if (publication) {
                        Thread.ofPlatform().daemon().start(() -> callback.complete(process));
                        await(publishing);
                      }
                    }
                    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                  }
                  if (timing == Timing.BEFORE) Thread.currentThread().interrupt();
                  return new ProcessHandle.Info() {
                    @Override public Optional<Instant> startInstant() { return Optional.empty(); }
                    @Override public Optional<String> command() { return info.command(); }
                    @Override public Optional<String> commandLine() { return info.commandLine(); }
                    @Override public Optional<String[]> arguments() { return info.arguments(); }
                    @Override public Optional<Duration> totalCpuDuration() { return info.totalCpuDuration(); }
                    @Override public Optional<String> user() { return info.user(); }
                  };
                }
                return info;
              }
              @Override public CompletableFuture<Process> onExit() { return callback; }
              @Override public long pid() { return process.pid(); }
              @Override public ProcessHandle toHandle() { return process.toHandle(); }
              @Override public boolean isAlive() {
                // Simulate the bounded lag between kernel exit and JDK liveness publication.
                if (!publication && ++aliveReads == 1) return true;
                return process.isAlive();
              }
              @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                boolean confirmation = ++waits == 1;
                if (confirmation && timing == Timing.DURING) {
                  Thread.currentThread().interrupt();
                  Thread.sleep(1);
                }
                if (Thread.interrupted()) throw new InterruptedException("before confirmation");
                boolean ended = process.waitFor(timeout, unit);
                if (confirmation && timing == Timing.AFTER) Thread.currentThread().interrupt();
                return ended;
              }
              @Override public int exitValue() { return process.exitValue(); }
              @Override public int waitFor() throws InterruptedException { return process.waitFor(); }
              @Override public void destroy() { process.destroy(); }
              @Override public Process destroyForcibly() { process.destroyForcibly(); return this; }
              @Override public InputStream getInputStream() { return process.getInputStream(); }
              @Override public InputStream getErrorStream() { return process.getErrorStream(); }
              @Override public OutputStream getOutputStream() { return process.getOutputStream(); }
            };
          });
    }

    StartResult start() {
      starter.set(Thread.currentThread());
      try {
        ChildHandle child = control.start(ChildControlHarness.spec("sleep"));
        return new StartResult(child, null, Thread.currentThread().isInterrupted());
      } catch (Exception failure) {
        return new StartResult(null, failure, Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }

    void finishProcess() throws IOException {
      process.getOutputStream().close();
      try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("real child did not exit");
      } catch (InterruptedException interrupted) {
        throw new AssertionError(interrupted);
      }
    }

    long exitRecords() throws IOException {
      return ExitLedgerReader.read(ledger, LedgerFamily.CHILDREN).records().stream()
          .filter(record -> record.type() == LedgerRecordType.CHILD_EXIT).count();
    }

    void assertExit(ChildHandle child) throws Exception {
      assertNotNull(child);
      ExitOutcome outcome = child.exit().toCompletableFuture().getNow(null);
      assertNotNull(outcome, "start returns only after publication");
      assertEquals(OptionalInt.of(37), outcome.code());
      assertEquals(ExitClass.UNEXPECTED, outcome.exitClass());
      assertEquals(Optional.empty(), outcome.reason());
      assertEquals(Optional.empty(), outcome.mode());
      assertEquals(TerminateResult.NOT_CALLED, outcome.terminateResult());
      assertEquals("real output\n", new String(child.stdout().readAllBytes(), StandardCharsets.UTF_8));
      assertEquals("real diagnostic\n", new String(child.stderr().readAllBytes(), StandardCharsets.UTF_8));
      callback.complete(process);
      assertSame(outcome, child.exit().toCompletableFuture().getNow(null));
      assertEquals(List.of(outcome), control.recentExits(32));
      assertTrue(outcome.ledgerDurable(), "the real writer persists despite the caller interrupt");
      assertTrue(writer.healthy());
      assertEquals(1, exitRecords(), "one child-exit record survives reload");
      var record = ExitLedgerReader.read(ledger, LedgerFamily.CHILDREN).records().stream()
          .filter(item -> item.type() == LedgerRecordType.CHILD_EXIT).findFirst().orElseThrow();
      assertEquals(37, record.field("code").asInt());
      assertEquals(Optional.empty(), record.text("intentId"));
      assertEquals(0, ExitLedgerReader.read(ledger, LedgerFamily.CHILDREN).records().stream()
          .filter(item -> item.type() == LedgerRecordType.STOP_INTENT).count());
    }

    @Override public void close() throws Exception {
      release.countDown();
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      }
      if (process != null) callback.complete(process);
      control.close();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("publication timed out");
    } catch (InterruptedException interrupted) {
      throw new AssertionError(interrupted);
    }
  }

  public static final class ExitChild {
    public static void main(String[] args) throws Exception {
      System.out.println("real output");
      System.err.println("real diagnostic");
      while (System.in.read() >= 0) { /* await EOF */ }
      System.exit(37);
    }
  }
}
