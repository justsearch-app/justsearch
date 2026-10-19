/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.child.TerminateResult;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Seams for driving the extraction pool through a {@link ChildControl} in tests. */
final class ChildControlFixtures {
  private ChildControlFixtures() {}

  /** A pool of one child over {@code control}, default policy, 500 requests per child. */
  static PersistentExtractionSandbox pool(
      List<String> command, Duration timeout, int maxRequests, ExtractionMetricCatalog catalog,
      ChildControl control) {
    return new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(), command,
        TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), timeout, 1, maxRequests,
        catalog, control);
  }

  /** One appended child record. */
  record Line(LedgerRecordType type, Map<String, Object> fields) {
    Object get(String field) {
      return fields.get(field);
    }
  }

  /** The children ledger as the control appends it, in order; every append is durable. */
  static final class RecordingLedger implements LedgerSink {
    final List<Line> lines = new CopyOnWriteArrayList<>();

    @Override
    public boolean append(LedgerRecordType type, Map<String, ?> fields) {
      lines.add(new Line(type, new LinkedHashMap<>(fields)));
      return true;
    }

    List<Line> of(LedgerRecordType type) {
      return lines.stream().filter(line -> line.type() == type).toList();
    }

    /** The childId the control gave the child with this PID. */
    String childId(long pid) {
      return of(LedgerRecordType.CHILD_START).stream()
          .filter(line -> Long.valueOf(pid).equals(line.get("pid")))
          .map(line -> (String) line.get("childId"))
          .findFirst()
          .orElseThrow(() -> new AssertionError("no child-start for pid " + pid + ": " + lines));
    }

    List<Line> of(LedgerRecordType type, long pid) {
      String childId = childId(pid);
      return of(type).stream().filter(line -> childId.equals(line.get("childId"))).toList();
    }

    /** The one child-exit of the child with this PID. */
    Line exitOf(long pid) {
      List<Line> exits = of(LedgerRecordType.CHILD_EXIT, pid);
      if (exits.size() != 1) {
        throw new AssertionError("expected one child-exit for pid " + pid + ": " + lines);
      }
      return exits.getFirst();
    }
  }

  /**
   * The OS terminator, except that while {@link #refuse} is set a terminate call fails (as an
   * access-denied {@code TerminateProcess} would) and the wait after it does not block.
   */
  static final class RefusingTerminator implements ProcessTerminator {
    final ProcessTerminator real = ProcessTerminator.forCurrentOs();
    final AtomicInteger terminateCalls = new AtomicInteger();
    volatile boolean refuse;

    RefusingTerminator(boolean refuse) {
      this.refuse = refuse;
    }

    @Override
    public OsProcess open(Process process) throws IOException {
      OsProcess os = real.open(process);
      return new OsProcess() {
        @Override
        public Optional<String> creationFileTime() {
          return os.creationFileTime();
        }

        @Override
        public TerminateResult terminate(int code) {
          terminateCalls.incrementAndGet();
          return refuse ? TerminateResult.failed(5) : os.terminate(code);
        }

        @Override
        public boolean awaitExit(Duration max) throws InterruptedException {
          return os.awaitExit(refuse ? Duration.ZERO : max);
        }

        @Override
        public OptionalInt exitCode() {
          return os.exitCode();
        }

        @Override
        public Optional<Instant> exitedAt() {
          return os.exitedAt();
        }

        @Override
        public void close() {
          os.close();
        }
      };
    }

    @Override
    public int ourCode() {
      return real.ourCode();
    }

    @Override
    public boolean codeDistinctive() {
      return real.codeDistinctive();
    }

    @Override
    public Optional<String> creationFileTime(long pid) {
      return real.creationFileTime(pid);
    }
  }

  /** A manifest registry that refuses every registration, as a full disk would. */
  static final class RefusingRegistry implements ManagedChildRegistry {
    final List<ManagedChild> attempted = new CopyOnWriteArrayList<>();

    @Override
    public List<ManagedChild> snapshot() {
      return List.of();
    }

    @Override
    public void register(ManagedChild child) throws IOException {
      attempted.add(child);
      throw new IOException("disk full");
    }

    @Override
    public void remove(String childId) {}
  }
}
