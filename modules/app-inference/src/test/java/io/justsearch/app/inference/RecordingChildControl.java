/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.ChildSurvivedException;
import io.justsearch.app.api.runtime.child.ChildView;
import io.justsearch.app.api.runtime.child.ExitClass;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.api.runtime.child.TerminateResult;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ChildControl} with no OS processes: handles are {@link Child} fakes, and every stop is
 * recorded with the request it was made with. A stop ends the child at once ({@code REQUESTED}),
 * unless the child was made a survivor, whose stop never completes or fails as {@link
 * ChildSurvivedException}.
 */
final class RecordingChildControl implements ChildControl {
  /** One recorded stop: the children it named and the request. */
  record Stop(List<ChildHandle> children, StopRequest request, boolean all) {}

  private final AtomicLong pids = new AtomicLong(40_000);
  final List<Stop> stops = new CopyOnWriteArrayList<>();
  final List<ChildSpec> started = new CopyOnWriteArrayList<>();

  /** How a child answers a stop. */
  enum OnStop { EXIT, HANG, SURVIVE }

  /** A held child: no process, an exit completed by a stop or by {@link #exitOnItsOwn}. */
  final class Child implements ChildHandle {
    private final String id = "c-fake-" + pids.incrementAndGet();
    private final long pid = pids.get();
    private final ChildKind kind;
    private final CompletableFuture<ExitOutcome> exit = new CompletableFuture<>();
    volatile OnStop onStop = OnStop.EXIT;
    private volatile boolean survived;

    Child(ChildKind kind) {
      this.kind = kind;
    }

    @Override public String childId() { return id; }
    @Override public ChildKind kind() { return kind; }
    @Override public long pid() { return pid; }
    @Override public Instant processStartedAt() { return Instant.EPOCH; }
    @Override public OutputStream stdin() { throw new UnsupportedOperationException(); }
    @Override public InputStream stdout() { throw new UnsupportedOperationException(); }
    @Override public InputStream stderr() { throw new UnsupportedOperationException(); }
    @Override public CompletionStage<ExitOutcome> exit() { return exit; }

    @Override
    public State state() {
      if (exit.isDone()) return State.EXITED;
      return survived ? State.SURVIVED : State.RUNNING;
    }

    /** Ends as an exit nobody asked for, with {@code code}. */
    void exitOnItsOwn(int code) {
      exit.complete(new ExitOutcome(id, kind, ExitClass.UNEXPECTED, Optional.empty(),
          Optional.empty(), Optional.empty(), OptionalInt.of(code), Optional.of(Instant.now()),
          Instant.now(), Optional.empty(), Optional.empty(), TerminateResult.NOT_CALLED, false,
          ExitOutcome.Phase.RUNNING, true));
    }

    private CompletableFuture<ExitOutcome> answer(StopRequest request) {
      switch (onStop) {
        case EXIT -> exit.complete(new ExitOutcome(id, kind, ExitClass.REQUESTED,
            Optional.of(request.reason()), request.detail(), Optional.of(request.mode()),
            OptionalInt.of(0x4A530001), Optional.of(Instant.now()), Instant.now(),
            Optional.of(Instant.now()), Optional.of(Instant.now()), TerminateResult.OK, true,
            ExitOutcome.Phase.STOPPING, true));
        case SURVIVE -> {
          survived = true;
          return CompletableFuture.failedFuture(new ChildSurvivedException(new ChildView(
              id, kind, pid, Instant.EPOCH, State.SURVIVED, false, "fake")));
        }
        case HANG -> { /* the exit never comes */ }
        default -> throw new IllegalStateException();
      }
      return exit;
    }
  }

  /** A held child this control would have started; it is recorded as started. */
  Child child(ChildKind kind) {
    return new Child(kind);
  }

  @Override
  public ChildHandle start(ChildSpec spec, ManifestEntry manifest) {
    started.add(spec);
    return new Child(spec.kind());
  }

  @Override
  public CompletionStage<ExitOutcome> stop(ChildHandle child, StopRequest request) {
    stops.add(new Stop(List.of(child), request, false));
    return ((Child) child).answer(request);
  }

  @Override
  public CompletionStage<List<ExitOutcome>> stopAll(
      Collection<ChildHandle> children, StopRequest request) {
    stops.add(new Stop(List.copyOf(children), request, true));
    List<CompletableFuture<ExitOutcome>> each = new ArrayList<>();
    for (ChildHandle child : children) each.add(((Child) child).answer(request));
    return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
        .thenApply(done -> each.stream().map(CompletableFuture::join).toList());
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
