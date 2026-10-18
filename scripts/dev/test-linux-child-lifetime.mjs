#!/usr/bin/env node
// Real installed Java seams and native programs, with identity-safe fixture cleanup.
import assert from 'node:assert/strict';
import { nativeTarget } from '../lib/native-targets.cjs';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, copyFileSync, symlinkSync, readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import net from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
if (process.platform !== 'linux') {
  console.log('Linux child-lifetime check is not applicable on this platform.');
  process.exit(0);
}
await campaign();

async function campaign() {
  const distribution = process.env.JUSTSEARCH_TEST_DISTRIBUTION
    ?? path.join(root, 'modules/ui/build/install/ui');
  const helper = path.join(distribution, nativeTarget().paths['bound-exec']);
  assert.ok(existsSync(helper), `Build :modules:ui:installDist first; missing ${helper}`);
  const scratch = mkdtempSync(path.join(tmpdir(), 'justsearch-child-lifetime-'));
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
  const javac = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/javac') : 'javac';
  const classpath = `${scratch}:${path.join(distribution, 'lib', '*')}`;
  const boot = readFileSync('/proc/sys/kernel/random/boot_id', 'utf8').trim();
  const owned = new Map();
  const output = [];
  let cleanupError;

  function identity(pid) {
    const stat = readFileSync(`/proc/${pid}/stat`, 'utf8');
    const fields = stat.slice(stat.lastIndexOf(')') + 2).trim().split(/\s+/);
    return { kind: 'linux-proc-v1', pid, bootId: boot, startTicks: fields[19], state: fields[0], parentPid: Number(fields[1]) };
  }
  function alive(record) {
    try {
      const live = identity(record.pid);
      return live.bootId === record.bootId && live.startTicks === record.startTicks
        && !['Z', 'X', 'x'].includes(live.state);
    } catch (error) {
      if (error.code === 'ENOENT') return false;
      throw error; // Unreadable is not ended.
    }
  }
  function signal(record, name) {
    const result = spawnSync(helper, ['signal', String(record.pid), record.bootId, record.startTicks, name], { encoding: 'utf8' });
    assert.ifError(result.error);
    assert.ok([0, 3].includes(result.status), `signal ${name}: ${result.stderr}`);
    return result.status;
  }
  function keep(pid) {
    const record = identity(pid);
    assert.match(record.startTicks, /^[1-9][0-9]*$/);
    owned.set(`${pid}/${record.startTicks}`, record);
    return record;
  }
  function receipt(record) {
    assert.equal(record.kind, 'linux-proc-v1');
    assert.equal(record.bootId, boot);
    assert.match(record.startTicks, /^[1-9][0-9]*$/);
    owned.set(`${record.pid}/${record.startTicks}`, record);
    return record;
  }
  async function until(check, bound, message) {
    const deadline = performance.now() + bound;
    do {
      const value = await check();
      if (value) return value;
      await delay(5);
    } while (performance.now() < deadline);
    throw new Error(message);
  }
  async function ended(record, bound) {
    const start = performance.now();
    await until(() => !alive(record), bound, `PID ${record.pid} survived ${bound} ms`);
    return Math.round((performance.now() - start) * 1000) / 1000;
  }
  function launch(mode, args = []) {
    const data = path.join(scratch, `${mode}-${output.length}`);
    mkdirSync(data, { recursive: true });
    const child = spawn(helper, ['exec', String(process.pid), '--', java, '--enable-native-access=ALL-UNNAMED', `-Djava.io.tmpdir=${scratch}`, '-cp', classpath,
      'io.justsearch.indexerworker.extract.LinuxLifetimeProbe', mode, data, ...args], {
      cwd: scratch, env: { ...process.env, JUSTSEARCH_TESSERACT_PATH: '/usr/bin/tesseract' },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    const owner = keep(child.pid);
    let stdout = '', stderr = '';
    child.stdout.on('data', (bytes) => { stdout += bytes; });
    child.stderr.on('data', (bytes) => { stderr += bytes; });
    child.on('error', (error) => { stderr += error.stack; });
    return { child, owner, data, stdout: () => stdout, stderr: () => stderr };
  }
  async function workload(run) {
    const pid = await until(() => {
      const match = run.stdout().match(/^CHILD (\d+)$/m);
      if (!alive(run.owner)) throw new Error(`Owner ended: ${run.stdout()}\n${run.stderr()}`);
      return match && Number(match[1]);
    }, 30_000, `Owner did not launch workload: ${run.stderr()}`);
    return keep(pid);
  }
  async function assertDeath(run, children, label) {
    await delay(100); // The short-lived launching thread has returned and exited.
    for (const record of children) assert.ok(alive(record), `${label}: child survived unrelated thread exit`);
    assert.equal(signal(run.owner, 'KILL'), 0);
    const times = [];
    const start = performance.now();
    for (const [index, record] of children.entries()) {
      const bound = index === 0 ? 1000 : 2000;
      await until(() => !alive(record), bound - (performance.now() - start), `${label}: PID ${record.pid} survived owner SIGKILL`);
      const ms = Math.round((performance.now() - start) * 1000) / 1000;
      times.push({ pid: record.pid, ms });
      assert.ok(ms <= bound, `${label}: cleanup exceeded ${bound} ms`);
    }
    output.push({ check: label, pass: true, times });
  }

  try {
    const source = path.join(scratch, 'LinuxLifetimeProbe.java');
    writeFileSync(source, javaProbe());
    const compiled = spawnSync(javac, ['-cp', path.join(distribution, 'lib', '*'), '-d', scratch, source], { encoding: 'utf8' });
    assert.ifError(compiled.error);
    assert.equal(compiled.status, 0, compiled.stdout + compiled.stderr);

    // A fresh JVM with application jars but no helper must truthfully refuse child work.
    const missing = path.join(scratch, 'unavailable', 'lib');
    mkdirSync(missing, { recursive: true });
    for (const name of readdirSync(path.join(distribution, 'lib')).filter((name) => name.endsWith('.jar'))) {
      const source = path.join(distribution, 'lib', name);
      const destination = path.join(missing, name);
      // Java canonicalizes jar symlinks in CodeSource; app-api must actually live without native/.
      if (name.startsWith('app-api-')) copyFileSync(source, destination);
      else symlinkSync(source, destination);
    }
    const unavailable = spawnSync(java, ['-cp', `${scratch}:${path.join(missing, '*')}`,
      'io.justsearch.indexerworker.extract.LinuxLifetimeProbe', 'unavailable', scratch], { encoding: 'utf8' });
    assert.ifError(unavailable.error);
    assert.equal(unavailable.status, 0, unavailable.stdout + unavailable.stderr);
    assert.match(unavailable.stdout, /UNAVAILABLE linux-bound-exec/);
    output.push({ check: 'missing-helper-refuses-child-work', pass: true });

    // Hold a real fork child before helper exec/prctl, kill its actual parent, then release it.
    const raceCode = `import os,sys,time,pathlib
root=pathlib.Path(sys.argv[1]); helper=sys.argv[2]; parent=os.getpid(); child=os.fork()
if child:
 print('RACE_CHILD '+str(child),flush=True)
 time.sleep(600)
else:
 while not (root/'prctl.gate').exists(): time.sleep(.001)
 os.execv(helper,[helper,'exec',str(parent),'--','/usr/bin/touch',str(root/'unreachable')])
`;
    const race = spawn(helper, ['exec', String(process.pid), '--', 'python3', '-u', '-c', raceCode, scratch, helper],
      { stdio: ['ignore', 'pipe', 'pipe'] });
    const raceOwner = keep(race.pid);
    let raceOut = '', raceError = '';
    race.stdout.on('data', (bytes) => { raceOut += bytes; });
    race.stderr.on('data', (bytes) => { raceError += bytes; });
    const racePid = await until(() => Number(raceOut.match(/RACE_CHILD (\d+)/)?.[1]), 5000, 'Fork race did not start');
    const raceChild = keep(racePid);
    assert.ok(alive(raceChild), 'child is held before prctl');
    assert.equal(identity(raceChild.pid).parentPid, raceOwner.pid, 'race uses the child’s actual parent');
    assert.equal(signal(raceOwner, 'KILL'), 0);
    await ended(raceOwner, 1000);
    assert.ok(alive(raceChild), 'uninstrumented fork survived parent death before prctl');
    writeFileSync(path.join(scratch, 'prctl.gate'), 'release');
    await ended(raceChild, 1000);
    await until(() => raceError.includes('parent identity mismatch'), 1000, raceError);
    assert.ok(!existsSync(path.join(scratch, 'unreachable')), 'workload must never execute after pre-prctl parent death');
    output.push({ check: 'parent-dead-before-prctl', pass: true });

    const decoy = spawn(helper, ['exec', String(process.pid), '--', 'python3', '-c', 'import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(600)'], { stdio: 'ignore' });
    const decoyIdentity = keep(decoy.pid);
    const ignoring = launch('ignoring');
    const ignoringChild = await workload(ignoring);
    await until(() => ignoring.stdout().includes('WORKLOAD_READY'), 10_000, ignoring.stderr());
    signal(ignoringChild, 'TERM');
    await delay(100);
    assert.ok(alive(ignoringChild), 'fixture really ignores SIGTERM');
    const wrongStart = spawnSync(helper, ['signal', String(decoyIdentity.pid), boot,
      (BigInt(decoyIdentity.startTicks) + 1n).toString(), 'KILL']);
    assert.equal(wrongStart.status, 3);
    const wrongBoot = spawnSync(helper, ['signal', String(decoyIdentity.pid),
      '00000000-0000-0000-0000-000000000000', decoyIdentity.startTicks, 'KILL']);
    assert.equal(wrongBoot.status, 3);
    await assertDeath(ignoring, [ignoringChild], 'SIGTERM-ignoring-child');
    assert.ok(alive(decoyIdentity), 'same-image foreign decoy survived');
    output.push({ check: 'bad-identity-and-same-image-decoy', pass: true });

    const tesseractDecoy = spawn(helper, ['exec', String(process.pid), '--', '/usr/bin/tesseract', 'stdin', 'stdout'], { stdio: ['pipe', 'ignore', 'ignore'] });
    const tesseractDecoyIdentity = keep(tesseractDecoy.pid);
    const parser = launch('parser');
    const parserIdentity = await workload(parser);
    const helperRecord = await until(() => {
      const file = path.join(parser.data, 'runtime/exit-ledger/children.v1.jsonl');
      if (!existsSync(file)) return false;
      return readFileSync(file, 'utf8').split('\n').flatMap((line) => {
        try { return [JSON.parse(line)]; } catch { return []; }
      }).find((record) => record.type === 'helper-start' && record.purpose === 'Tesseract page OCR');
    }, 30_000, `Actual parser did not spawn page OCR: ${parser.stderr()}`);
    const tesseract = receipt(helperRecord.processIdentity);
    const ocrWindow = readFileSync(path.join(parser.data, 'runtime/exit-ledger/children.v1.jsonl'), 'utf8')
      .split('\n').filter(Boolean).map((line) => JSON.parse(line))
      .find((row) => row.type === 'helper-window-start'
        && row.reason === 'Tesseract OCR during extraction' && row.owner?.pid === parserIdentity.pid);
    assert.ok(ocrWindow, 'actual OCR parse opened its owner window');
    assert.equal(ocrWindow.image, path.basename(helperRecord.executable),
      'OCR owner window names the executable actually launched');
    assert.ok(alive(tesseract), 'Tesseract is running at crash injection');
    assert.equal(helperRecord.owner?.pid, parserIdentity.pid, 'parser wrote its own OCR receipt');
    assert.equal(identity(tesseract.pid).parentPid, parserIdentity.pid, 'real parser spawned Tesseract');
    assert.equal(signal(tesseract, 'STOP'), 0); // Bound lifetime must also end a stopped native child.
    await assertDeath(parser, [parserIdentity, tesseract], 'real-parser-and-tesseract');
    assert.ok(alive(tesseractDecoyIdentity), 'same-name foreign Tesseract survived');

    const server = process.env.JUSTSEARCH_SERVER_EXE;
    assert.ok(server && existsSync(server), 'JUSTSEARCH_SERVER_EXE must name the operator GPU runtime');
    const common = spawnSync('git', ['rev-parse', '--path-format=absolute', '--git-common-dir'], {
      cwd: root, encoding: 'utf8',
    });
    assert.ifError(common.error);
    assert.equal(common.status, 0, common.stderr);
    const models = process.env.JUSTSEARCH_MODELS_DIR ?? path.join(path.dirname(common.stdout.trim()), 'models');
    const compact = path.join(models, 'compact');
    const model = [compact, models].flatMap((dir) => readdirSync(dir).filter((name) => name.endsWith('.gguf') && !name.startsWith('mmproj'))
      .map((name) => path.join(dir, name)))[0];
    assert.ok(model, 'A read-only retained GGUF model is required');
    const listener = net.createServer();
    await new Promise((resolve) => listener.listen(0, '127.0.0.1', resolve));
    const port = listener.address().port;
    await new Promise((resolve) => listener.close(resolve));
    const llama = launch('llama', [server, '-m', model, '--host', '127.0.0.1', '--port', String(port),
      '-ngl', '999', '-c', '1024', '--parallel', '1', '--no-warmup']);
    const llamaIdentity = await workload(llama);
    await until(async () => {
      if (!alive(llamaIdentity)) throw new Error(`llama-server ended: ${llama.stderr()}`);
      try { return (await fetch(`http://127.0.0.1:${port}/health`)).ok; } catch { return false; }
    }, 120_000, `GPU llama-server did not become ready: ${llama.stderr()}`);
    assert.match(llama.stderr(), /offloaded [1-9][0-9]*\/\d+ layers to GPU/, 'real GPU offload, no CPU fallback');
    await assertDeath(llama, [llamaIdentity], 'GPU-llama-server');
    for (const row of output) console.log(JSON.stringify(row));
  } finally {
    for (const record of [...owned.values()].reverse()) {
      try {
        if (alive(record)) signal(record, 'KILL');
        await ended(record, 2000);
      } catch (error) { cleanupError ??= error; }
    }
    if (!cleanupError) rmSync(scratch, { recursive: true, force: true });
    else console.error(`Fixture cleanup unresolved; retained evidence: ${scratch}`);
    if (cleanupError) throw cleanupError;
  }
}

function javaProbe() { return String.raw`
package io.justsearch.indexerworker.extract;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.*;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import tools.jackson.databind.ObjectMapper;
public final class LinuxLifetimeProbe {
  public static void main(String[] args) throws Exception {
    var containment = EngineContainment.installAtBoot();
    if (args[0].equals("unavailable")) {
      if (containment.status() != EngineContainment.Status.UNAVAILABLE)
        throw new AssertionError("Missing helper reported " + containment);
      try {
        OwnedHelper.start(new ProcessBuilder("/bin/true"), "must refuse");
        throw new AssertionError("Child work ran without containment");
      } catch (IOException expected) {
        System.out.println("UNAVAILABLE " + containment.unavailableReason().orElseThrow());
      }
      return;
    }
    if (containment.status() != EngineContainment.Status.CONTAINED)
      throw new IllegalStateException("Child lifetime unavailable: " + containment);
    Path data = Path.of(args[1]);
    var control = DefaultChildControl.openForEngine(data, "linux-lifetime-check", ManagedChildRegistry.noop(), VramProbe.none(), containment);
    java.util.List<String> argv;
    ChildKind kind = ChildKind.EXTRACTION;
    if (args[0].equals("parser")) {
      argv = new ArrayList<>(java.util.List.of(ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED", "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
        "-cp", System.getProperty("java.class.path"),
        ExtractionSandboxChild.class.getName(), "--parent-pid=" + ProcessHandle.current().pid()));
      argv.addAll(OwnedHelper.childArguments());
    } else if (args[0].equals("llama")) {
      argv = java.util.List.of(Arrays.copyOfRange(args, 2, args.length));
      kind = ChildKind.LLAMA_SERVER;
    } else {
      argv = java.util.List.of("python3", "-u", "-c",
        "import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); print('WORKLOAD_READY',flush=True); time.sleep(600)");
    }
    var spec = new ChildSpec(kind, argv, data, Map.of(), new ChildSpec.Stdio.Pipes(), false,
      OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "linux-lifetime-check");
    AtomicReference<ChildHandle> child = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    Thread launcher = Thread.ofPlatform().start(() -> {
      try { child.set(control.start(spec)); } catch (Throwable failure) { error.set(failure); }
    });
    launcher.join(); // Must not be the thread to which PDEATHSIG is attached.
    if (error.get() != null) throw new RuntimeException(error.get());
    ChildHandle held = child.get();
    System.out.println("CHILD " + held.pid()); System.out.flush();
    Thread.ofPlatform().daemon().start(() -> { try { held.stderr().transferTo(System.err); } catch (IOException ignored) {} });
    if (args[0].equals("parser")) {
      BufferedImage image = new BufferedImage(4096, 4096, BufferedImage.TYPE_BYTE_GRAY);
      Graphics2D graphics = image.createGraphics();
      graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 4096, 4096);
      graphics.setColor(Color.BLACK); graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
      for (int y = 24; y < 4096; y += 23)
        graphics.drawString("JUSTSEARCH LINUX LIFETIME OCR 0123456789 ".repeat(6), 2, y);
      graphics.dispose();
      Path input = data.resolve("scan.png"); ImageIO.write(image, "png", input.toFile());
      var request = new SandboxExtractionRequest(2, "lifetime-ocr", input.toString(), TikaExtractionPolicy.defaults(),
        new OcrRoutingConfig(true, java.util.List.of("eng"), 30000, null, null, null, null, 1));
      byte[] frame = new ObjectMapper().writeValueAsBytes(request);
      DataOutputStream pipe = new DataOutputStream(held.stdin()); pipe.writeInt(frame.length); pipe.write(frame); pipe.flush();
    } else {
      Thread.ofPlatform().daemon().start(() -> { try { held.stdout().transferTo(System.out); } catch (IOException ignored) {} });
    }
    Thread.sleep(Long.MAX_VALUE);
  }
}
`; }
