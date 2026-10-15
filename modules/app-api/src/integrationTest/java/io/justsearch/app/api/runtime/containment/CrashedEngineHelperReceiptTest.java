/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.containment;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.child.*;
import io.justsearch.app.api.runtime.ledger.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** A disposable Engine job crashes with a live helper; its successor writes the missing outcome. */
@EnabledOnOs(OS.WINDOWS)
@Timeout(90)
class CrashedEngineHelperReceiptTest {
  @TempDir Path data;
  @Test void successorRecordsCrashConsequenceAndUnknownExitTime() throws Exception {
    exerciseCrashReceipt(false);
  }
  @Test void delayedSuccessorReceiptSurvivesFinalStopCensus() throws Exception {
    exerciseCrashReceipt(true);
  }
  private void exerciseCrashReceipt(boolean delayed) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        Holder.class.getName(), data.toString()).redirectErrorStream(true);
    builder.environment().put("JUSTSEARCH_ENGINE_INCARNATION", "crash-helper/1");
    Process fixture = builder.start();
    var held = ProcessTerminator.forCurrentOs().open(fixture);
    String birth = held.creationFileTime().orElseThrow();
    // As a real supervisor does (the shell's actuator, shell_actuator.rs): the one writer that records
    // the Engine's exit records its start, with the native birth it reads (GetProcessTimes).
    var writer = ExitLedgerWriter.appendOnly(LedgerFamily.directory(data), LedgerFamily.ENGINE,
        LedgerWriterId.currentProcess(LedgerWriterId.Role.SHELL,
            ProcessHandle.current().info().startInstant().orElseThrow()), Clock.systemUTC());
    writer.append(LedgerRecordType.ENGINE_START, Map.of("pid", fixture.pid(),
        "creationFileTime", birth,
        "executable", ProcessHandle.current().info().command().orElseThrow(),
        "incarnation", new RuntimeManifest.IncarnationInfo("crash-helper", 1)));
    long helperPid = 0;
    try {
      var output = new BufferedReader(new InputStreamReader(fixture.getInputStream(), StandardCharsets.UTF_8));
      for (String line; (line = output.readLine()) != null;) {
        if (line.startsWith("READY ")) { helperPid = Long.parseLong(line.substring(6)); break; }
      }
      assertTrue(helperPid > 0, "fixture identity " + fixture.pid() + "/" + birth);
      assertEquals(TerminateResult.OK, held.terminate(1));
      assertTrue(fixture.waitFor(10, TimeUnit.SECONDS));
      writer.append(LedgerRecordType.ENGINE_EXIT, Map.of("incarnation", new RuntimeManifest.IncarnationInfo("crash-helper", 1),
          "exitClass", "UNEXPECTED", "code", 1, "observedAt", java.time.Instant.now()));
      if (delayed) Thread.sleep(2500); // Beyond the census's 2 s containment grace.
      // The previous Engine's job has closed; the successor only observes identities.
      try (var next = DefaultChildControl.openForEngine(data, "successor", ManagedChildRegistry.noop(),
          VramProbe.none(), EngineContainment.current())) {
        assertNotNull(next, "the successor must open its child control to reconcile crash receipts");
      }
      long target = helperPid;
      var records = ExitLedgerReader.read(LedgerFamily.directory(data), LedgerFamily.CHILDREN).records();
      var receipt = records.stream().filter(r -> r.type() == LedgerRecordType.JOB_PROCESS_EXIT
          && r.field("pid").asLong(0) == target).findFirst().orElseThrow(() ->
              new AssertionError("witnessed crash casualty has no final owner receipt"));
      assertEquals("OWNER_DIED", receipt.text("exitClass").orElseThrow());
      assertEquals("ENGINE_CRASH", receipt.text("cause").orElseThrow());
      assertTrue(receipt.text("reason").orElseThrow().contains("Engine crash"));
      assertTrue(receipt.field("exitedAt").isNull(), "observation time must not invent an exit time");
      assertTrue(receipt.field("code").isNull());
      if (delayed) assertFinalCensus(fixture.pid(), target);
    } finally {
      if (fixture.isAlive()) held.terminate(1);
      held.close();
    }
  }
  private void assertFinalCensus(long enginePid, long helperPid) throws Exception {
    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (!Files.exists(root.resolve("scripts/dev/lib/stop-exit-census.cjs"))) {
      root = root.getParent();
      assertNotNull(root, "repository census must be available");
    }
    String script = """
        const fs = require('node:fs'), assert = require('node:assert/strict');
        const [module, data, engine, helper] = process.argv.slice(1), c = require(module);
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
        const ledger = c.readRunLedger(data), view = c.ledgerView(ledger, 'crash-helper');
        const lookupEnded = await stopLookup(view);
        const input = c.exitTargets({runId:'crash-helper',pids:{backendRootPid:Number(engine)}}, null, [], ledger);
        const result = c.exitResult(input, {processExits:[]}, 'native delayed receipt', ledger, null, { lookupEnded });
        fs.writeFileSync(require('node:path').join(data, 'final-census.json'), JSON.stringify(result, null, 2));
        assert.ok(view.witnesses.some(w => w.pid === Number(helper)));
        for (const w of view.witnesses.filter(w => w.pid !== Number(engine))) {
          const row = result.processExits.find(r => r.pid === w.pid && r.creationFileTimeUtc === w.creationFileTimeUtc);
          assert.equal(row?.ledgerClass, 'OWNER_DIED', 'delayed owner crash receipt lost by final stop census');
          assert.equal(row.atMs, null);
          assert.equal(row.exitTimeSource, 'unknown');
          assert.equal(row.expectedTermination, false);
          assert.ok(!result.exitAccountingGaps.some(g => g.pid === w.pid), JSON.stringify(result.exitAccountingGaps));
        }
        })().catch(error => { console.error(error); process.exit(1); });
        """;
    Path output = data.resolve("final-census-check.log");
    Process node = new ProcessBuilder("node", "-e", script,
        root.resolve("scripts/dev/lib/stop-exit-census.cjs").toString(), data.toString(),
        Long.toString(enginePid), Long.toString(helperPid)).redirectErrorStream(true)
        .redirectOutput(output.toFile()).start();
    var held = ProcessTerminator.forCurrentOs().open(node);
    String birth = held.creationFileTime().orElseThrow();
    try {
      assertTrue(node.waitFor(15, TimeUnit.SECONDS), "census check identity " + node.pid() + "/" + birth);
      assertEquals(0, node.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    } finally {
      if (node.isAlive()) held.terminate(1);
      held.close();
    }
  }
  public static class Holder {
    public static void main(String[] args) throws Exception {
      var job = EngineContainment.installAtBoot();
      assertEquals(EngineContainment.Status.CONTAINED, job.status());
      Path data = Path.of(args[0]);
      // Its start is its supervisor's record (the test's), as the Engine never writes its own.
      try (var control = DefaultChildControl.openForEngine(data, "crash-owner", ManagedChildRegistry.noop(),
          VramProbe.none(), job)) {
        assertNotNull(control, "the crashing owner must install its child control before starting helpers");
        Process helper = OwnedHelper.start(new ProcessBuilder("ping.exe", "-t", "127.0.0.1")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD), "crash fixture helper");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ExitLedgerReader.read(LedgerFamily.directory(data), LedgerFamily.CHILDREN).records().stream()
            .noneMatch(r -> r.type() == LedgerRecordType.JOB_PROCESS_START && r.field("pid").asLong(0) == helper.pid())) {
          if (System.nanoTime() > until) throw new AssertionError("helper not witnessed");
          Thread.sleep(20);
        }
        System.out.println("READY " + helper.pid()); System.out.flush();
        Thread.sleep(Duration.ofSeconds(60));
      }
    }
  }
}
