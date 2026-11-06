/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildRegistrationRefusedException;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.ChildView;
import io.justsearch.app.api.runtime.child.ExitClass;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.api.runtime.child.TerminateResult;
import io.justsearch.app.inference.chat.ChildSelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Design D4, repair round 5: a launch whose manifest registration was refused is rolled back; that
 * rollback can end the root while ending what it started fails, so the exception hands over a child
 * that has EXITED but whose retained scope still lists a running descendant. Both owners that read
 * the answer model's files as in use (the managed-server launch owner and the self-test launcher)
 * keep that rolled-back child until its root and its descendants are confirmed gone; the removal
 * refuses meanwhile. The descendant is a real process; the control is a fake that refuses every
 * registration this way.
 */
@Tag("cross-platform")
final class RolledBackChildOwnershipTest {
  @TempDir Path tmp;

  private Process descendant;

  @AfterEach
  void endDescendant() throws InterruptedException {
    if (descendant != null && descendant.isAlive()) {
      descendant.destroyForcibly();
      descendant.waitFor(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void theLaunchOwnerKeepsARolledBackServerUntilItsSurvivingDescendantIsGone() throws Exception {
    var ownership = new LlamaServerProcessOwnership(new RefusingControl(rolledBack()));
    assertFalse(ownership.anyOwned(), "precondition: nothing is owned");

    var refused = assertThrows(ChildRegistrationRefusedException.class,
        () -> ownership.launch(() -> {
          throw new ChildRegistrationRefusedException(new IOException("disk full"), rolledBack());
        }));
    assertTrue(refused.survivor().isEmpty(), "precondition: the rolled-back root has exited");
    assertTrue(descendant.isAlive(), "precondition: what it started still runs");

    assertTrue(ownership.anyOwned(),
        "a rolled-back server whose descendant survived still holds the answer model's files");
    assertTrue(ownership.anyOwned(), "and keeps holding them while the descendant runs");

    descendant.destroyForcibly();
    assertTrue(descendant.waitFor(10, TimeUnit.SECONDS), "precondition: the descendant ended");
    assertTrue(eventually(() -> !ownership.anyOwned()),
        "once the root and its descendants are confirmed gone the files are released");
  }

  @Test
  void theSelfTestLauncherKeepsARolledBackCandidateUntilItsSurvivingDescendantIsGone()
      throws Exception {
    ChildHandle candidate = rolledBack();
    var launcher = new ChildSelfTestLauncher(new RefusingControl(candidate));
    assertFalse(launcher.candidatesMayHoldFiles(), "precondition: nothing runs");

    SelfTestRun run = launcher.run(new SelfTestSpec(List.of("llama-server", "-m", "model.gguf"),
        null, List.of(), tmp.resolve("self-test.log"), 1, Duration.ofSeconds(1),
        new SelfTestSpec.Probe() {
          @Override
          public boolean healthy(int port) {
            return true;
          }

          @Override
          public void exercise(int port) {}
        }, Map.of()));

    assertFalse(run.exercised(), "precondition: the candidate never started: " + run.failure());
    assertTrue(run.failure().contains("disk full"), "precondition: its registration was refused: "
        + run.failure());
    assertSame(ChildHandle.State.EXITED, candidate.state(), "precondition: its root has exited");
    assertTrue(descendant.isAlive(), "precondition: what it started still runs");
    assertTrue(launcher.candidatesMayHoldFiles(),
        "a rolled-back candidate whose descendant survived still holds the answer model's files");

    descendant.destroyForcibly();
    assertTrue(descendant.waitFor(10, TimeUnit.SECONDS), "precondition: the descendant ended");
    assertTrue(eventually(() -> !launcher.candidatesMayHoldFiles()),
        "once the root and its descendants are confirmed gone the files are released");
  }

  @Test
  void aRolledBackChildWhoseScopeCannotBeReadIsNotConfirmedGone() throws Exception {
    var ownership = new LlamaServerProcessOwnership(new RefusingControl(null));
    ChildHandle unreadable = new ExitedChild(null);
    assertThrows(ChildRegistrationRefusedException.class, () -> ownership.launch(() -> {
      throw new ChildRegistrationRefusedException(new IOException("disk full"), unreadable);
    }));
    assertTrue(ownership.anyOwned(), "an unreadable descendant list counts as in use");
  }

  /** A rolled-back child: its root exited, a real process it started still runs. */
  private ChildHandle rolledBack() throws IOException {
    if (descendant == null) {
      String java = Path.of(System.getProperty("java.home"), "bin",
          System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
      String classes;
      try {
        classes = Path.of(ManagedLlamaAdoptionTest.SleepingChild.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toString();
      } catch (java.net.URISyntaxException e) {
        throw new IOException(e);
      }
      descendant = new ProcessBuilder(java, "-cp", classes,
          ManagedLlamaAdoptionTest.SleepingChild.class.getName()).start();
    }
    return new ExitedChild(descendant.toHandle());
  }

  private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) return false;
      Thread.sleep(20);
    }
    return true;
  }

  /** A child whose exit was recorded; its retained scope lists {@code left}, or cannot be read. */
  private static final class ExitedChild implements ChildHandle {
    private final ProcessHandle left;
    private final CompletableFuture<ExitOutcome> exit;

    ExitedChild(ProcessHandle left) {
      this.left = left;
      this.exit = CompletableFuture.completedFuture(new ExitOutcome("c-rolled-back",
          ChildKind.LLAMA_SERVER, ExitClass.REQUESTED, Optional.of(StopReason.ROLLBACK),
          Optional.empty(), Optional.of(StopMode.NOW), OptionalInt.of(1),
          Optional.of(Instant.now()), Instant.now(), Optional.of(Instant.now()),
          Optional.of(Instant.now()), TerminateResult.OK, true, ExitOutcome.Phase.STOPPING, true));
    }

    @Override public String childId() { return "c-rolled-back"; }
    @Override public ChildKind kind() { return ChildKind.LLAMA_SERVER; }
    @Override public long pid() { return -1; }
    @Override public Instant processStartedAt() { return Instant.EPOCH; }
    @Override public OutputStream stdin() { throw new UnsupportedOperationException(); }
    @Override public InputStream stdout() { throw new UnsupportedOperationException(); }
    @Override public InputStream stderr() { throw new UnsupportedOperationException(); }
    @Override public CompletionStage<ExitOutcome> exit() { return exit; }
    @Override public State state() { return State.EXITED; }

    @Override
    public List<ProcessHandle> descendants() {
      // Ending the scope failed: the control still lists what is left, until it exits.
      if (left == null) throw new IllegalStateException("descendant enumeration unavailable");
      return left.isAlive() ? List.of(left) : List.of();
    }
  }

  /** A control whose every start is a refused registration handing over {@code rolledBack}. */
  private static final class RefusingControl implements ChildControl {
    private final ChildHandle rolledBack;

    RefusingControl(ChildHandle rolledBack) {
      this.rolledBack = rolledBack;
    }

    @Override
    public ChildHandle start(ChildSpec spec, ManifestEntry manifest) throws IOException {
      throw new ChildRegistrationRefusedException(new IOException("disk full"), rolledBack);
    }

    @Override
    public CompletionStage<ExitOutcome> stop(ChildHandle child, StopRequest request) {
      return child.exit();
    }

    @Override
    public CompletionStage<List<ExitOutcome>> stopAll(
        Collection<ChildHandle> children, StopRequest request) {
      return CompletableFuture.completedFuture(
          children.stream().map(child -> child.exit().toCompletableFuture().join()).toList());
    }

    @Override
    public void note(ChildHandle child, String token) {}

    @Override
    public boolean awaitGpuRelease(Duration max) {
      return true;
    }

    @Override
    public List<ChildView> list() {
      return List.of();
    }

    @Override
    public List<ExitOutcome> recentExits(int max) {
      return List.of();
    }

    @Override
    public void close() {}
  }
}
