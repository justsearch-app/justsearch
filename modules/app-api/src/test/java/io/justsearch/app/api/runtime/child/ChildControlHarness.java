/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.api.runtime.ledger.LedgerWriterId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One {@link DefaultChildControl} over a real children ledger in a temp directory, with the seams
 * Spec B §8 names: a hooked terminator around the real one, a ledger sink that logs the order of
 * events, a recording manifest registry, and optionally a virtual clock and VRAM probe.
 */
final class ChildControlHarness implements AutoCloseable {
  static final Instant EPOCH = Instant.parse("2026-10-06T09:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static Schema schema;

  final Path ledgerDir;
  final ExitLedgerWriter writer;
  final List<String> events = Collections.synchronizedList(new ArrayList<>());
  final HookedTerminator terminator = new HookedTerminator(ProcessTerminator.forCurrentOs());
  final RecordingRegistry registry = new RecordingRegistry();
  volatile AppendHook appendHook = (type, fields, next) -> next.append(type, fields);
  Clock clock = Clock.systemUTC();
  DefaultChildControl.Sleeper sleeper = DefaultChildControl.Sleeper.REAL;
  VramProbe vram = VramProbe.none();
  DefaultChildControl.JobMembership job = DefaultChildControl.JobMembership.NONE;
  private DefaultChildControl control;

  ChildControlHarness(Path dir) {
    this.ledgerDir = LedgerFamily.directory(dir);
    this.writer = ExitLedgerWriter.rotating(ledgerDir, LedgerFamily.CHILDREN,
        LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, Instant.now()), Clock.systemUTC());
  }

  /** A harness whose clock only moves when the control sleeps (GPU polls). */
  ChildControlHarness virtualTime() {
    VirtualClock virtual = new VirtualClock(EPOCH);
    clock = virtual;
    sleeper = virtual::advance;
    return this;
  }

  DefaultChildControl control() {
    if (control == null) {
      LedgerSink sink = (type, fields) -> {
        events.add("append:" + type.token());
        return appendHook.around(type, fields, writer::append);
      };
      control = new DefaultChildControl(sink, terminator, vram, clock, sleeper, registry,
          new EngineIdentity(ProcessHandle.current().pid(), Optional.empty(), "inst-test"),
          Thread.ofPlatform().daemon().name("test-child-control-", 0).factory(), job);
    }
    return control;
  }

  static ChildSpec spec(String mode) {
    return spec(mode, ChildSpec.CooperativeStop.NONE);
  }

  static ChildSpec spec(String mode, ChildSpec.CooperativeStop cooperative) {
    return new ChildSpec(ChildKind.EXTRACTION, FakeChild.argv(mode), null, Map.of(),
        new ChildSpec.Stdio.Pipes(), false, OptionalLong.empty(), Optional.empty(), cooperative,
        "fake-" + mode.split(":")[0]);
  }

  static ChildSpec gpuSpec(String mode, OptionalLong footprint) {
    return new ChildSpec(ChildKind.LLAMA_SERVER, FakeChild.argv(mode), null, Map.of(),
        new ChildSpec.Stdio.Pipes(), true, footprint,
        Optional.of(new GpuDevice(GpuDevice.luidOf(0, 0x1234), OptionalInt.of(0))),
        ChildSpec.CooperativeStop.NONE, "fake-gpu");
  }

  static ChildSpec.CooperativeStop closeStdin(Duration grace, Integer... expected) {
    return new ChildSpec.CooperativeStop(
        ChildSpec.CooperativeStop.Action.CLOSE_STDIN, grace, Set.of(expected));
  }

  static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(60, TimeUnit.SECONDS);
  }

  List<LedgerRecord> records() {
    try {
      return ExitLedgerReader.read(ledgerDir, LedgerFamily.CHILDREN).records();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  List<LedgerRecord> records(LedgerRecordType type) {
    return records().stream().filter(r -> r.type() == type).toList();
  }

  List<LedgerRecord> records(LedgerRecordType type, String childId) {
    return records(type).stream()
        .filter(r -> r.text("childId").filter(childId::equals).isPresent()).toList();
  }

  List<String> types(String childId) {
    return records().stream()
        .filter(r -> r.text("childId").filter(childId::equals).isPresent())
        .map(r -> r.type().token()).toList();
  }

  /** Every line in the children ledger conforms to governance/exit-ledger.v1.schema.json. */
  void assertLedgerConforms() throws IOException {
    Path file = LedgerFamily.CHILDREN.current(ledgerDir);
    if (!Files.exists(file)) return;
    for (String line : Files.readAllLines(file)) {
      if (line.isBlank()) continue;
      var errors = schema().validate(JSON.readTree(line));
      assertTrue(errors.isEmpty(), () -> line + " -> " + errors);
    }
  }

  static synchronized Schema schema() throws IOException {
    if (schema == null) {
      Path root = Path.of("").toAbsolutePath();
      while (!Files.isDirectory(root.resolve("governance"))) root = root.getParent();
      JsonNode node = JSON.readTree(
          Files.readString(root.resolve("governance/exit-ledger.v1.schema.json")));
      SchemaRegistry registry =
          SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
      SchemaContext context = new SchemaContext(
          registry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), registry);
      schema = context.newSchema(
          SchemaLocation.of("https://ssot.justsearch/v1/governance/exit-ledger.v1.schema.json"),
          node, null);
    }
    return schema;
  }

  @Override
  public void close() {
    if (control != null) control.close();
    terminator.killAll();
  }

  /** Wraps a ledger append. */
  @FunctionalInterface
  interface AppendHook {
    boolean around(LedgerRecordType type, Map<String, ?> fields, LedgerSink next);
  }

  /** Runs before a terminate call, with the child's process. */
  @FunctionalInterface
  interface TerminateHook {
    void before(Process process) throws Exception;
  }

  /** The real terminator with hooks: ordering events, a pre-terminate action, a never-kill mode. */
  final class HookedTerminator implements ProcessTerminator {
    final ProcessTerminator real;
    final List<Process> processes = Collections.synchronizedList(new ArrayList<>());
    final List<OsProcess> ownedHandles = Collections.synchronizedList(new ArrayList<>());
    final AtomicLong virtualWaitMs = new AtomicLong();
    volatile TerminateHook beforeTerminate;
    /** Terminate reports OK but kills nothing, and waits on a survivor return at once. */
    volatile boolean neverKill;

    HookedTerminator(ProcessTerminator real) {
      this.real = real;
    }

    @Override
    public OsProcess open(Process process) throws IOException {
      processes.add(process);
      OsProcess os = real.open(process);
      ownedHandles.add(os); // PID and birth remain pinned on the same native handle until cleanup
      return new OsProcess() {
        @Override
        public Optional<String> creationFileTime() {
          return os.creationFileTime();
        }

        @Override
        public TerminateResult terminate(int code) {
          events.add("terminate");
          TerminateHook hook = beforeTerminate;
          if (hook != null) {
            try {
              hook.before(process);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
          if (neverKill) return TerminateResult.OK;
          return os.terminate(code);
        }

        @Override
        public boolean awaitExit(Duration max) throws InterruptedException {
          if (neverKill && process.isAlive()) {
            virtualWaitMs.addAndGet(max.toMillis());
            return false;
          }
          return os.awaitExit(max);
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

    void killAll() {
      synchronized (ownedHandles) {
        for (OsProcess os : ownedHandles) {
          if (os.exitCode().isEmpty()) os.terminate(real.ourCode());
          os.close();
        }
      }
    }
  }

  /** The manifest registry, recorded; it can be told to refuse a registration. */
  static final class RecordingRegistry implements ManagedChildRegistry {
    final List<ManagedChild> registered = Collections.synchronizedList(new ArrayList<>());
    final List<String> removed = Collections.synchronizedList(new ArrayList<>());
    volatile boolean refuse;
    /** Runs inside register, after the entry is recorded and before register returns. */
    volatile RegisterHook duringRegister = entry -> {};

    interface RegisterHook {
      void run(ManagedChild entry) throws IOException;
    }

    @Override
    public List<ManagedChild> snapshot() {
      synchronized (registered) {
        return registered.stream().filter(c -> !removed.contains(c.id())).toList();
      }
    }

    @Override
    public void register(ManagedChild child) throws IOException {
      if (refuse) throw new IOException("manifest write refused");
      registered.add(child);
      duringRegister.run(child);
    }

    @Override
    public void remove(String childId) {
      removed.add(childId);
    }
  }

  /** Adapter memory from a script: one value per read, the last repeated. */
  static final class ScriptedVram implements VramProbe {
    private final GpuMemorySource source;
    private final List<Long> values;
    final AtomicLong reads = new AtomicLong();
    volatile ReadHook onRead = n -> {};

    ScriptedVram(GpuMemorySource source, Long... values) {
      this.source = source;
      this.values = List.of(values);
    }

    @Override
    public GpuMemorySource source() {
      return source;
    }

    @Override
    public OptionalLong adapterUsedBytes(Optional<GpuDevice> device) {
      long n = reads.incrementAndGet();
      try {
        onRead.read(n);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return OptionalLong.empty();
      }
      return OptionalLong.of(values.get((int) Math.min(n - 1, values.size() - 1)));
    }
  }

  /** Runs on each probe read, with its 1-based number. */
  @FunctionalInterface
  interface ReadHook {
    void read(long n) throws InterruptedException;
  }

  /** A clock that moves only when advanced. */
  static final class VirtualClock extends Clock {
    private final AtomicLong nanos;
    private final Instant base;

    VirtualClock(Instant base) {
      this.base = base;
      this.nanos = new AtomicLong();
    }

    void advance(Duration duration) {
      nanos.addAndGet(duration.toNanos());
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return base.plusNanos(nanos.get());
    }
  }
}
