/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.child.*;
import io.justsearch.app.api.runtime.ledger.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Real wrapper, interrupted probe and pre-job Engine console identities, without a dev stack. */
@EnabledOnOs(OS.WINDOWS)
@Timeout(90)
class LauncherConsoleReceiptTest {
  @TempDir Path data;

  @Test void realLauncherChildInheritsOwnerIntent() throws Exception {
    runFixture("wrapper");
    assertCensus("wrapper");
  }

  @Test void preInterruptedRealProbeKeepsStartAndFinalOwnerReceipt() throws Exception {
    runFixture("probe");
    assertCensus("probe");
  }

  @Test void crashedEnginesOwnConsoleGetsSuccessorCrashReceipt() throws Throwable {
    try (NativeLaunch engine = new NativeLaunch(command(Holder.class, data.toString(), "console"))) {
      var writer = supervise(engine);
      awaitFile(data.resolve("ready"));
      engine.captureConsole(); // Independent oracle, including on the unrepaired before-state.
      for (var start : ExitLedgerReader.read(LedgerFamily.directory(data), LedgerFamily.CHILDREN).records()) {
        if (start.type() == LedgerRecordType.JOB_PROCESS_START) System.out.println("native-job-start="
            + start.field("pid").asLong(0) + "/" + start.text("creationFileTime").orElse("unknown"));
      }
      engine.terminate();
      writer.append(LedgerRecordType.ENGINE_EXIT, Map.of("incarnation", incarnation(),
          "exitClass", "UNEXPECTED", "code", 1, "observedAt", Instant.now()));
      Thread.sleep(2500);
      try (var next = DefaultChildControl.openForEngine(data, "successor", ManagedChildRegistry.noop(),
          VramProbe.none(), EngineContainment.current())) { }
      var records = ExitLedgerReader.read(LedgerFamily.directory(data), LedgerFamily.CHILDREN).records();
      var console = records.stream().filter(r -> r.type() == LedgerRecordType.JOB_PROCESS_START
          && r.field("consoleClient").asBoolean(false)
          && r.field("parentPid").asLong(0) == engine.pid).findFirst().orElseThrow(() ->
              new AssertionError("Engine's own pre-job console host was not witnessed"));
      assertEquals(engine.birth, console.text("parentCreationFileTime").orElseThrow());
      var receipt = records.stream().filter(r -> r.type() == LedgerRecordType.JOB_PROCESS_EXIT
          && r.text("witnessId").equals(console.text("witnessId"))).findFirst().orElseThrow();
      assertEquals("OWNER_DIED", receipt.text("exitClass").orElseThrow());
      assertEquals("ENGINE_CRASH", receipt.text("cause").orElseThrow());
      assertTrue(receipt.field("exitedAt").isNull());
      assertCensus("console");
    }
  }

  @Test void unsampledEnginesOwnConsoleIsAccountedOnCleanQuit() throws Throwable {
    try (NativeLaunch engine = new NativeLaunch(command(Holder.class, data.toString(), "clean-console"))) {
      var writer = supervise(engine);
      awaitFile(data.resolve("ready"));
      engine.captureConsole();
      assertCensus("clean-arm");
      Files.writeString(data.resolve("quit"), "QUIT", StandardCharsets.UTF_8);
      engine.awaitNaturalExit(data.resolve("native-exits.json"));
      writer.append(LedgerRecordType.ENGINE_EXIT, Map.of("incarnation", incarnation(), "exitClass", "REQUESTED",
          "reason", "QUIT", "code", 0, "observedAt", Instant.now()));
      assertCensus("clean-final");
      try (var next = DefaultChildControl.openForEngine(data, "successor", ManagedChildRegistry.noop(),
          VramProbe.none(), EngineContainment.current())) { }
      assertCensus("clean-history");
    }
  }

  private void runFixture(String mode) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command(Holder.class, data.toString(), mode))
        .redirectErrorStream(true).redirectOutput(data.resolve("fixture.log").toFile());
    builder.environment().put("JUSTSEARCH_ENGINE_INCARNATION", "launcher-console/1");
    Process process = builder.start();
    var held = ProcessTerminator.forCurrentOs().open(process);
    try {
      String birth = held.creationFileTime().orElseThrow();
      System.out.println("fixture-start=" + process.pid() + "/" + birth);
      try {
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(data.resolve("fixture.log"), StandardCharsets.UTF_8));
      } finally {
        if (process.isAlive()) {
          assertEquals(birth, held.creationFileTime().orElseThrow());
          held.terminate(1);
        }
      }
    } finally { held.close(); }
  }

  private void assertCensus(String mode) throws Exception {
    Path root = Path.of(System.getProperty("census.test.tree", System.getProperty("user.dir"))).toAbsolutePath();
    while (!Files.exists(root.resolve("scripts/dev/lib/stop-exit-census.cjs"))) root = Objects.requireNonNull(root.getParent());
    String script = """
        const assert = require('node:assert/strict'), fs = require('node:fs');
        const [module, data, mode] = process.argv.slice(1), c = require(module);
        // The stop's identity-checked lookup (dev-runner stopRun): every start the ledger leaves
        // unproven is looked up, and the observations go to the verdict (lookupObservation).
        const { lookupProcessIdentities } = require(require('node:path').join(require('node:path').dirname(module), 'engine-actuator.cjs'));
        const stopLookup = async (view) => {
          const unproven = c.ledgerResolution(view).unproven, askedAtMs = Date.now();
          const rows = await lookupProcessIdentities(unproven.map(p => p.pid));
          assert.ok(rows, 'identity-checked lookup answered');
          return unproven.map(p => c.lookupObservation(p, rows.get(p.pid), askedAtMs)).filter(Boolean);
        };
        (async () => {
        const ledger = c.readRunLedger(data), view = c.ledgerView(ledger, 'launcher-console');
        const records = ledger.children.records;
        if (mode === 'wrapper') {
          const child = records.find(r => r.type === 'job-process-start' && r.line.pid === Number(fs.readFileSync(data+'/child','utf8')));
          assert.ok(child, 'real wrapper child witnessed');
          const w = view.witnesses.find(w => w.pid === child.line.pid);
          assert.equal(w.exit.exitClass, 'REQUESTED', 'real child inherits owned launcher intent');
          assert.match(w.exit.reason, /launcher/);
        } else if (mode === 'probe') {
          const receipt = records.find(r => r.type === 'helper-exit' && r.line.reason?.includes('interrupted'));
          assert.ok(receipt, 'interrupted owner receipt');
          assert.ok(records.some(r => r.type === 'helper-start' && r.line.helperId === receipt.line.helperId), 'pre-interrupted start retained');
          const w = view.witnesses.find(w => w.pid === receipt.line.pid);
          assert.equal(w.exit.exitClass, 'REQUESTED', 'interrupted witness and owner agree');
        } else if (mode === 'clean-arm') {
          const w = view.witnesses.find(w => w.consoleClient);
          assert.ok(w, 'native own-console witness retained before clean quit');
          const input = c.exitTargets({runId:'launcher-console',pids:{backendRootPid:w.engine.pid}}, null, [], ledger);
          assert.ok(input.targets.some(t => t.pid === w.pid && t.witnessId === w.witnessId), 'unsampled console armed');
          fs.writeFileSync(data+'/armed.json', JSON.stringify(input));
          // The stop looks its identities up once before it acts, while the Engine still runs.
          fs.writeFileSync(data+'/lookup-ended.json', JSON.stringify(await stopLookup(view)));
        } else if (mode === 'clean-history') {
          const w = view.witnesses.find(w => w.consoleClient);
          assert.equal(w.exit.exitClass,'REQUESTED', 'surviving owner records ended intended console');
          assert.equal(w.exit.code,null);
          assert.equal(w.exit.exitedAtMs,null);
          const input = c.exitTargets({runId:'launcher-console',pids:{backendRootPid:w.engine.pid}}, null, [], ledger);
          const result = c.exitResult(input,{processExits:[]},'historical clean console',ledger,null,{ lookupEnded: await stopLookup(view) });
          assert.equal(result.processExits.find(r => r.pid === w.pid).ledgerClass,'REQUESTED');
          assert.deepEqual(result.exitAccountingGaps,[]);
        } else if (mode === 'clean-final') {
          const input = JSON.parse(fs.readFileSync(data+'/armed.json','utf8'));
          const output = {processExits:JSON.parse(fs.readFileSync(data+'/native-exits.json','utf8')).map(exit => {
            const target = input.targets.find(t => t.pid === exit.pid);
            assert.ok(target, 'native exit belongs to armed target');
            return {...exit, osCreationFileTimeUtc:exit.creationFileTimeUtc, creationFileTimeUtc:target.creationFileTimeUtc};
          })};
          const lookupEnded = JSON.parse(fs.readFileSync(data+'/lookup-ended.json','utf8'));
          const result = c.exitResult(input,output,'native clean quit',ledger,null,{ lookupEnded });
          assert.equal(result.processExits.find(r => r.role === 'console-host').ledgerClass,'REQUESTED');
          assert.equal(result.processExits.filter(r => r.ledgerClass === 'UNEXPECTED').length,0);
          assert.deepEqual(result.exitAccountingGaps,[]);
          assert.equal(result.exitAccountingComplete,true);
        } else {
          const w = view.witnesses.find(w => w.consoleClient);
          assert.ok(w, 'Engine console captured');
          assert.equal(w.exit.exitClass, 'OWNER_DIED');
          assert.equal(w.exit.exitedAtMs, null);
          const start = records.find(r => r.type === 'job-process-start' && r.line.witnessId === w.witnessId);
          const seen = {pid:w.pid,parentPid:w.engine.pid,creationFileTimeUtc:w.creationFileTimeUtc,
            name:'conhost.exe',lastSeenAtMs:Date.parse(start.line.observedAt)};
          const lookupEnded = await stopLookup(view);
          for (const observed of [[], [seen]]) {
            const input = c.exitTargets({runId:'launcher-console',pids:{backendRootPid:w.engine.pid}}, null, observed, ledger);
            const result = c.exitResult(input, {processExits:[]}, 'native console', ledger, null, { lookupEnded });
            const row = result.processExits.find(r => r.pid === w.pid);
            assert.equal(row.ledgerClass, 'OWNER_DIED');
            assert.equal(row.expectedTermination, false);
            assert.equal(row.atMs, null);
            assert.ok(!result.exitAccountingGaps.some(g => g.pid === w.pid));
          }
        }
        })().catch(error => { console.error(error); process.exit(1); });
        """;
    Path log = data.resolve("census.log");
    Process node = new ProcessBuilder("node", "-e", script,
        root.resolve("scripts/dev/lib/stop-exit-census.cjs").toString(), data.toString(), mode)
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    var held = ProcessTerminator.forCurrentOs().open(node);
    try {
      String birth = held.creationFileTime().orElseThrow();
      System.out.println("census-start=" + node.pid() + "/" + birth);
      try {
        assertTrue(node.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, node.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
      } finally {
        if (node.isAlive()) { assertEquals(birth, held.creationFileTime().orElseThrow()); held.terminate(1); }
      }
    } finally { held.close(); }
  }

  private static RuntimeManifest.IncarnationInfo incarnation() {
    return new RuntimeManifest.IncarnationInfo("launcher-console", 1);
  }
  /**
   * The test as the Engine's supervisor (the dev runner's actuator, the shell's): it records the start
   * of the Engine it launched, with the birth its CIM lookup reads (to the microsecond), and its exit
   * with the same writer.
   */
  private ExitLedgerWriter supervise(NativeLaunch engine) {
    var writer = engineWriter(data);
    writer.append(LedgerRecordType.ENGINE_START, Map.of("pid", engine.pid,
        "creationFileTime", Long.toString(Long.parseLong(engine.birth) / 10 * 10),
        "executable", ProcessHandle.current().info().command().orElseThrow(), "incarnation", incarnation()));
    return writer;
  }
  private static ExitLedgerWriter engineWriter(Path dir) {
    return ExitLedgerWriter.appendOnly(LedgerFamily.directory(dir), LedgerFamily.ENGINE,
        LedgerWriterId.currentProcess(LedgerWriterId.Role.DEV_RUNNER,
            ProcessHandle.current().info().startInstant().orElseThrow()), Clock.systemUTC());
  }
  private static List<String> command(Class<?> main, String... args) {
    List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), main.getName()));
    command.addAll(List.of(args)); return command;
  }
  private static void awaitFile(Path ready) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!Files.exists(ready)) { assertTrue(System.nanoTime() < until, "fixture ready deadline"); Thread.sleep(20); }
  }

  public static class Holder {
    public static void main(String[] args) throws Exception {
      Path dir = Path.of(args[0]); String mode = args[1];
      var job = EngineContainment.installAtBoot();
      assertEquals(EngineContainment.Status.CONTAINED, job.status());
      var engine = engineWriter(dir);
      // In the console modes the test is the Engine's supervisor and records its start and exit
      // (supervise); the other modes keep the records the fixture writes for itself.
      boolean supervised = mode.endsWith("console");
      if (!supervised) engine.append(LedgerRecordType.ENGINE_START, Map.of("pid", ProcessHandle.current().pid(),
          "creationFileTime", ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()).orElseThrow(),
          "executable", ProcessHandle.current().info().command().orElseThrow(), "incarnation", incarnation()));
      try (var control = DefaultChildControl.openForEngine(dir, "fixture", ManagedChildRegistry.noop(), VramProbe.none(), job)) {
        if (mode.endsWith("console")) {
          Process helper = OwnedHelper.start(new ProcessBuilder("ping.exe", "-t", "127.0.0.1")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD), "crash job helper");
          long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          while (ExitLedgerReader.read(LedgerFamily.directory(dir), LedgerFamily.CHILDREN).records().stream()
              .noneMatch(r -> r.type() == LedgerRecordType.JOB_PROCESS_START && r.field("pid").asLong(0) == helper.pid())) {
            assertTrue(System.nanoTime() < until, "live job helper witnessed"); Thread.sleep(20);
          }
          Files.writeString(dir.resolve("ready"), "ready");
          if (mode.equals("console")) Thread.sleep(60000);
          else {
            awaitFile(dir.resolve("quit"));
            OwnedHelper.terminate(helper, "QUIT");
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS));
          }
        } else if (mode.equals("wrapper")) {
          Process wrapper = OwnedHelper.start(new ProcessBuilder(command(Wrapper.class, dir.toString())), "owned launcher");
          assertTrue(wrapper.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, wrapper.exitValue());
        } else {
          Thread.currentThread().interrupt();
          Process probe = OwnedHelper.start(new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
              "-Command", "Start-Sleep -Seconds 30"), "disk encryption probe");
          try { probe.waitFor(); fail("pre-interrupted wait must throw"); }
          catch (InterruptedException expected) { }
          finally { Thread.interrupted(); }
          assertTrue(probe.waitFor(10, TimeUnit.SECONDS));
        }
        Thread.sleep(200); job.finishProcessRecording(Duration.ofSeconds(3));
      }
      if (!supervised) engine.append(LedgerRecordType.ENGINE_EXIT, Map.of("incarnation", incarnation(), "exitClass", "REQUESTED",
          "reason", "QUIT", "code", 0, "observedAt", Instant.now()));
    }
  }
  public static class Wrapper {
    public static void main(String[] args) throws Exception {
      Process child = new ProcessBuilder(command(Child.class)).start();
      var held = ProcessTerminator.forCurrentOs().open(child);
      try {
        String birth = held.creationFileTime().orElseThrow();
        Files.writeString(Path.of(args[0], "child"), Long.toString(child.pid()));
        System.out.println("wrapper-child-start=" + child.pid() + "/" + birth);
        try { assertTrue(child.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, child.exitValue()); }
        finally { if (child.isAlive()) { assertEquals(birth, held.creationFileTime().orElseThrow()); held.terminate(1); } }
      } finally { held.close(); }
    }
  }
  public static class Child {
    public static void main(String[] args) throws Exception { Thread.sleep(1000); }
  }

  /** CREATE_NEW_CONSOLE gives this fixture its own real host before its kill-on-close job exists. */
  private static final class NativeLaunch implements AutoCloseable {
    final Arena arena = Arena.ofConfined();
    final Linker linker = Linker.nativeLinker();
    final SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
    final MemorySegment handle;
    final long pid;
    final String birth;
    MemorySegment consoleHandle = MemorySegment.NULL;
    long consolePid;
    String consoleBirth;
    final MethodHandle times = bind("GetProcessTimes", ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    NativeLaunch(List<String> command) throws Throwable {
      MemorySegment startup = arena.allocate(104, 8), info = arena.allocate(24, 8);
      startup.set(ValueLayout.JAVA_INT, 0, 104);
      startup.set(ValueLayout.JAVA_INT, 60, 1); // STARTF_USESHOWWINDOW
      startup.set(ValueLayout.JAVA_SHORT, 64, (short) 0); // SW_HIDE
      var env = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER); env.putAll(System.getenv());
      env.put("JUSTSEARCH_ENGINE_INCARNATION", "launcher-console/1");
      String block = String.join("\0", env.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()) + "\0\0";
      String cmd = String.join(" ", command.stream().map(s -> "\"" + s + "\"").toList());
      int ok = (int) bind("CreateProcessW", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
          .invokeExact(MemorySegment.NULL, wide(cmd + "\0"), MemorySegment.NULL, MemorySegment.NULL,
              0, 0x410, wide(block), MemorySegment.NULL, startup, info);
      assertNotEquals(0, ok, "CreateProcessW");
      handle = info.get(ValueLayout.ADDRESS, 0); pid = Integer.toUnsignedLong(info.get(ValueLayout.JAVA_INT, 16));
      birth = readBirth(); closeHandle(info.get(ValueLayout.ADDRESS, 8));
      System.out.println("native-engine-start=" + pid + "/" + birth);
    }
    MemorySegment wide(String text) {
      MemorySegment out = arena.allocate(text.length() * 2L, 2);
      for (int i = 0; i < text.length(); i++) out.set(ValueLayout.JAVA_CHAR, i * 2L, text.charAt(i));
      return out;
    }
    String readBirth() throws Throwable {
      return readBirth(handle);
    }
    String readBirth(MemorySegment process) throws Throwable {
      MemorySegment c = arena.allocate(8), e = arena.allocate(8), k = arena.allocate(8), u = arena.allocate(8);
      assertNotEquals(0, (int) times.invokeExact(process, c, e, k, u));
      return Long.toString(c.get(ValueLayout.JAVA_LONG, 0));
    }
    void captureConsole() throws Throwable {
      assertEquals(258, (int) bind("WaitForSingleObject", ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.JAVA_INT).invokeExact(handle, 0));
      MemorySegment snapshot = (MemorySegment) bind("CreateToolhelp32Snapshot", ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT, ValueLayout.JAVA_INT).invokeExact(2, 0);
      assertNotEquals(-1L, snapshot.address());
      try {
        MemorySegment entry = arena.allocate(568, 8); entry.set(ValueLayout.JAVA_INT, 0, 568);
        int found = (int) bind("Process32FirstW", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeExact(snapshot, entry);
        while (found != 0) {
          if (Integer.toUnsignedLong(entry.get(ValueLayout.JAVA_INT, 32)) == pid) {
            long candidate = Integer.toUnsignedLong(entry.get(ValueLayout.JAVA_INT, 8));
            MemorySegment h = (MemorySegment) bind("OpenProcess", ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT).invokeExact(0x101000, 0, (int) candidate);
            if (h.address() != 0) {
              boolean keep = false;
              try {
                MemorySegment size = arena.allocate(4), name = arena.allocate(2048, 2);
                size.set(ValueLayout.JAVA_INT, 0, 1024);
                int ok = (int) bind("QueryFullProcessImageNameW", ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeExact(h, 0, name, size);
                if (ok != 0) {
                  char[] chars = new char[size.get(ValueLayout.JAVA_INT, 0)];
                  for (int i = 0; i < chars.length; i++) chars[i] = name.get(ValueLayout.JAVA_CHAR, i * 2L);
                  if (new String(chars).toLowerCase(Locale.ROOT).endsWith("\\conhost.exe")) {
                    MemorySegment basic = arena.allocate(48, 8);
                    SymbolLookup nt = SymbolLookup.libraryLookup("ntdll", Arena.global());
                    MethodHandle query = linker.downcallHandle(nt.find("NtQueryInformationProcess").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                    assertEquals(0, (int) query.invokeExact(h, 0, basic, 48, MemorySegment.NULL));
                    assertEquals(pid, basic.get(ValueLayout.JAVA_LONG, 40));
                    assertEquals(birth, readBirth());
                    String hostBirth = readBirth(h);
                    assertTrue(Long.parseLong(birth) <= Long.parseLong(hostBirth));
                    assertEquals(258, (int) bind("WaitForSingleObject", ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT).invokeExact(h, 0));
                    System.out.println("native-engine-console-start=" + candidate + "/" + hostBirth + " creator=" + pid + "/" + birth);
                    consoleHandle = h; consolePid = candidate; consoleBirth = hostBirth; keep = true; return;
                  }
                }
              } finally { if (!keep) closeHandle(h); }
            }
          }
          found = (int) bind("Process32NextW", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeExact(snapshot, entry);
        }
        fail("native oracle: Engine console missing before crash");
      } finally { closeHandle(snapshot); }
    }
    void terminate() throws Throwable {
      assertEquals(birth, readBirth());
      int state = (int) bind("WaitForSingleObject", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT).invokeExact(handle, 0);
      if (state == 258) {
        System.out.println("native-engine-before-kill=" + pid + "/" + birth);
        assertNotEquals(0, (int) bind("TerminateProcess", ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT).invokeExact(handle, 1));
      }
      assertEquals(0, (int) bind("WaitForSingleObject", ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT).invokeExact(handle, 10000));
    }
    void awaitNaturalExit(Path output) throws Throwable {
      assertEquals(0, (int) bind("WaitForSingleObject", ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT).invokeExact(handle, 15000));
      assertEquals(0, (int) bind("WaitForSingleObject", ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT).invokeExact(consoleHandle, 10000));
      Files.writeString(output, "[" + exitJson(handle, pid, birth) + ","
          + exitJson(consoleHandle, consolePid, consoleBirth) + "]", StandardCharsets.UTF_8);
    }
    String exitJson(MemorySegment process, long processPid, String processBirth) throws Throwable {
      assertEquals(processBirth, readBirth(process));
      MemorySegment c = arena.allocate(8), e = arena.allocate(8), k = arena.allocate(8), u = arena.allocate(8);
      assertNotEquals(0, (int) times.invokeExact(process, c, e, k, u));
      MemorySegment code = arena.allocate(4);
      assertNotEquals(0, (int) bind("GetExitCodeProcess", ValueLayout.JAVA_INT,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS).invokeExact(process, code));
      assertTrue(e.get(ValueLayout.JAVA_LONG, 0) > 0);
      return "{\"pid\":" + processPid + ",\"creationFileTimeUtc\":\"" + processBirth
          + "\",\"exitCode\":" + code.get(ValueLayout.JAVA_INT, 0) + ",\"atMs\":"
          + ((e.get(ValueLayout.JAVA_LONG, 0) - 116444736000000000L) / 10000) + "}";
    }
    void closeHandle(MemorySegment h) throws Throwable { int ignored = (int) bind("CloseHandle", ValueLayout.JAVA_INT, ValueLayout.ADDRESS).invokeExact(h); }
    MethodHandle bind(String name, MemoryLayout result, MemoryLayout... args) {
      return linker.downcallHandle(kernel.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
    }
    @Override public void close() throws Exception {
      try { terminate(); if (consoleHandle.address() != 0) closeHandle(consoleHandle); closeHandle(handle); }
      catch (Throwable failure) { throw new Exception(failure); }
      finally { arena.close(); }
    }
  }
}
