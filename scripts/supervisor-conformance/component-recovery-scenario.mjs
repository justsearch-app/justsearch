import fs from 'node:fs';
import path from 'node:path';
import identity from '../dev/lib/process-identity.cjs';

const SESSION_HEADER = 'X-JustSearch-Session';
const FAMILY = 'generative-recovery-1';

export function stagePrivateGenerativeRuntime({ data, modelsRoot, env, requireThat }) {
  const source = path.join(path.dirname(modelsRoot), 'modules', 'ui', 'native-bin',
    'llama-server', 'variants', 'cuda12');
  const target = path.join(data, 'native-bin', 'llama-server');
  const executable = path.join(target, 'llama-server.exe');
  const profile = env.JUSTSEARCH_CHAT_PROFILE;
  const profileFiles = {
    compact: ['compact/Qwen3.5-4B-Q4_K_M.gguf', 'compact/mmproj-F16.gguf'],
    standard: ['Qwen_Qwen3.5-9B-Q4_K_M.gguf', 'mmproj-F16.gguf'],
  };
  const selected = profileFiles[profile];
  requireThat(selected, `generative recovery requires an explicit canonical chat profile: ${profile}`);
  const model = path.join(modelsRoot, selected[0]);
  const mmproj = path.join(modelsRoot, selected[1]);
  requireThat(fs.existsSync(path.join(source, 'llama-server.exe')),
    'generative recovery requires the retained cuda12 llama-server runtime');
  requireThat(fs.existsSync(model) && fs.statSync(model).isFile(),
    `generative recovery requires the retained ${profile} GGUF`);
  requireThat(fs.existsSync(mmproj) && fs.statSync(mmproj).isFile(),
    `generative recovery requires the retained ${profile} projector`);
  fs.mkdirSync(target, { recursive: true });
  for (const name of fs.readdirSync(source)) {
    const from = path.join(source, name);
    if (!fs.statSync(from).isFile()) continue;
    fs.linkSync(from, path.join(target, name));
  }
  env.JUSTSEARCH_SERVER_EXE = executable;
  env.JUSTSEARCH_MODELS_DIR = modelsRoot;
  delete env.JUSTSEARCH_LLM_MODEL_PATH;
  delete env.JUSTSEARCH_VLM_MODEL;
  delete env.JUSTSEARCH_MMPROJ_MODEL;
  delete env.JUSTSEARCH_VLM_PROFILE;
  env.JUSTSEARCH_GPU_ENABLED = 'true';
  env.JUSTSEARCH_GENERATIVE_RECOVERY_BARRIER = '1';
  delete env.AI_OFFLINE;
  return { executable, model, mmproj, profile };
}

export async function exerciseGenerativeRecovery(c) {
  const { scenario, work, data, first, manifest, apiPort, privateRuntime, readJson, waitFor,
    request, requireThat } = c;
  const exhaustion = scenario === 'generative-recovery-exhaustion';
  requireThat(exhaustion || scenario === 'generative-recovery-success',
    `unknown generative recovery scenario: ${scenario}`);
  const runtime = path.join(data, 'runtime');
  const reachedFile = path.join(runtime, `${FAMILY}-reached.json`);
  const releaseFile = path.join(runtime, `${FAMILY}-release`);
  const heldExecutable = `${privateRuntime.executable}.held-absent`;
  requireThat(!fs.existsSync(reachedFile) && !fs.existsSync(releaseFile)
      && !fs.existsSync(heldExecutable),
  'generative recovery fixture must start without barrier or held-runtime residue');

  let executableHeld = false;
  try {
    const activation = await requestOnlineIntent(apiPort, manifest, request);
    requireThat(activation.status === 200 && activation.body?.success === true
        && activation.body?.requested === 'online'
        && (activation.body?.state === 'recorded' || activation.body?.state === 'converged')
        && typeof activation.body?.operationKey === 'string'
        && activation.body.operationKey.length > 0
        && Number.isSafeInteger(activation.body?.acceptedRevision)
        && activation.body.acceptedRevision > 0,
    `generative activation intent was not recorded: ${JSON.stringify(activation)}`);
    const ready = await waitFor('real generative child READY', 180000, async () => {
      try {
        const response = await request(apiPort, '/api/status', {}, 15000);
        if (response.status !== 200) return null;
        const status = JSON.parse(response.text);
        const row = status.readiness?.engineComponents?.generative;
        const current = readJson(path.join(runtime, 'manifest.json'));
        const child = current?.children?.find(value => value.kind === 'LLAMA_SERVER');
        return row?.state === 'READY' && row.recoveryAttempts === 0 && child
          && current.chat?.profileId === privateRuntime.profile
          && current.chat?.modelFile === path.basename(privateRuntime.model)
          && current.chat?.mmprojActive === true ? { status, row, child, manifest: current } : null;
      } catch { return null; }
    });
    requireThat(first.pid === manifest.pid && first.instanceId === manifest.instanceId
        && ready.manifest.pid === first.pid && ready.manifest.instanceId === first.instanceId,
    'generative recovery may fault only the admitted Engine incarnation');
    requireThat(samePath(ready.child.executable, privateRuntime.executable)
        && samePath(ready.child.modelPath, privateRuntime.model),
    `managed child did not use the fixture-private runtime/model: ${JSON.stringify(ready.child)}`);

    const table = identity.readProcessTable();
    requireThat(table.ok, `cannot verify managed generative child: ${table.reason}`);
    const observed = table.table.find(row => Number(row.ProcessId) === ready.child.pid);
    const recordedStart = Date.parse(ready.child.startedAt);
    const observedStart = fileTimeToEpochMs(observed?.CreationFileTimeUtc);
    requireThat(observed && Number.isFinite(recordedStart) && Number.isFinite(observedStart)
        && Math.abs(recordedStart - observedStart) <= 1000
        && observed.CommandLine?.toLowerCase().includes(privateRuntime.executable.toLowerCase()),
    `managed child identity does not match the live process table: ${JSON.stringify({
      child: ready.child, observed,
    })}`);
    const record = { pid: ready.child.pid,
      creationFileTimeUtc: observed.CreationFileTimeUtc,
      cmdlineFingerprint: observed.CommandLine };
    const verified = identity.verifyProcessIdentity({ record, table: identity.readProcessTable() });
    requireThat(identity.isVerifiedMatch(verified),
      `refusing unverified generative child fault: ${verified.reason}`);
    process.kill(ready.child.pid, 'SIGKILL');

    const reached = await waitFor('generative recovery pre-admission barrier', 30000,
      () => readJson(reachedFile));
    requireThat(reached.point === 'component-recovery-pre-admission'
        && reached.component === 'generative' && reached.state === 'FAILED'
        && reached.recoveryAttempts === 0 && reached.pid === first.pid
        && reached.appliedVersion === ready.row.appliedVersion
        && reached.desiredVersion === ready.row.desiredVersion,
    `generative recovery barrier marker changed identity: ${JSON.stringify(reached)}`);
    const held = await statusRow(apiPort, request, 'generative');
    requireThat(held?.state === 'FAILED' && held.recoveryAttempts === 0
        && held.stateSince === reached.stateSince && held.reasonCode === reached.reasonCode
        && held.evidence === reached.evidence
        && held.appliedVersion === reached.appliedVersion
        && held.desiredVersion === reached.desiredVersion,
    `barrier did not preserve the exact FAILED/count0 observation: ${JSON.stringify(held)}`);
    const busy = await recover(apiPort, manifest, request);
    requireThat(busy.status === 429 && busy.body?.errorCode === 'ADMISSION_ENGINE_LIMIT',
      `manual recovery during held automatic attempt was not busy: ${JSON.stringify(busy)}`);

    await waitFor('crashed managed child exit', 15000, () => {
      const current = identity.readProcessTable();
      return current.ok && !current.table.some(row => Number(row.ProcessId) === ready.child.pid)
        ? { observedAt: new Date().toISOString() } : null;
    });
    fs.renameSync(privateRuntime.executable, heldExecutable);
    executableHeld = true;
    fs.writeFileSync(releaseFile, new Date().toISOString());

    let failedOneLast = null;
    let failedOneLastMatching = null;
    let failedOne;
    try {
      failedOne = await waitFor('generative recovery attempt 1 failure', 60000, async () => {
        const row = await statusRow(apiPort, request, 'generative');
        failedOneLast = { observedAt: new Date().toISOString(), row };
        if (row?.state === 'FAILED' && row.recoveryAttempts === 1) {
          failedOneLastMatching = failedOneLast;
        }
        return missingPrivateExecutableFailure(row, 1, privateRuntime.executable) ? row : null;
      });
    } catch (error) {
      writeAttemptFailure(work, 1, privateRuntime.executable, failedOneLast,
        failedOneLastMatching, error);
      throw error;
    }
    let accepted;
    let terminal;
    if (!exhaustion) {
      fs.renameSync(heldExecutable, privateRuntime.executable);
      executableHeld = false;
      accepted = await recoverAfterSlotCleanup(apiPort, manifest, request, 15000);
      requireThat(accepted.status === 202 && accepted.body?.component === 'generative'
          && accepted.body?.recovery === 'ACCEPTED',
      `manual generative recovery was not accepted: ${JSON.stringify(accepted)}`);
      terminal = await waitFor('generative recovery attempt 2 success', 180000, async () => {
        const row = await statusRow(apiPort, request, 'generative');
        return row?.state === 'READY' && row.recoveryAttempts === 2 ? row : null;
      });
      const replacement = await waitFor('replacement managed generative child', 30000, () => {
        const current = readJson(path.join(runtime, 'manifest.json'));
        const child = current?.children?.find(value => value.kind === 'LLAMA_SERVER');
        return child && child.id !== ready.child.id && child.pid !== ready.child.pid
          && child.startedAt !== ready.child.startedAt ? child : null;
      });
      requireThat(samePath(replacement.executable, privateRuntime.executable)
          && samePath(replacement.modelPath, privateRuntime.model),
      `replacement child changed the captured runtime/model: ${JSON.stringify(replacement)}`);
      const replacementTable = identity.readProcessTable();
      requireThat(replacementTable.ok,
        `cannot verify replacement managed child: ${replacementTable.reason}`);
      const replacementObserved = replacementTable.table.find(
        row => Number(row.ProcessId) === replacement.pid);
      const replacementStart = Date.parse(replacement.startedAt);
      const replacementObservedStart = fileTimeToEpochMs(replacementObserved?.CreationFileTimeUtc);
      requireThat(replacementObserved && Number.isFinite(replacementStart)
          && Number.isFinite(replacementObservedStart)
          && Math.abs(replacementStart - replacementObservedStart) <= 1000
          && replacementObserved.CommandLine?.toLowerCase()
            .includes(privateRuntime.executable.toLowerCase()),
      `replacement child identity does not match the live process table: ${JSON.stringify({
        replacement, replacementObserved,
      })}`);
      const replacementVerified = identity.verifyProcessIdentity({
        record: { pid: replacement.pid,
          creationFileTimeUtc: replacementObserved.CreationFileTimeUtc,
          cmdlineFingerprint: replacementObserved.CommandLine },
        table: identity.readProcessTable(),
      });
      requireThat(identity.isVerifiedMatch(replacementVerified),
        `replacement child failed exact live identity verification: ${replacementVerified.reason}`);

      const chatHeaders = { 'content-type': 'application/json', accept: 'text/event-stream' };
      const token = manifest.head?.sessionToken;
      if (typeof token === 'string' && token.length > 0) chatHeaders[SESSION_HEADER] = token;
      const chatResponse = await request(apiPort, '/api/chat/agent', {
        method: 'POST', headers: chatHeaders, body: JSON.stringify({
          messages: [{ role: 'user',
            content: 'Reply with exactly the word quokka and nothing else.' }],
          maxIterations: 1,
          sampling: { temperature: 0.0, top_p: 1.0, seed: 5475 },
        }),
      }, 180000);
      const chatTerminal = doneEvent(chatResponse.text);
      requireThat(chatResponse.status === 200 && chatTerminal
          && typeof chatTerminal.finalResponse === 'string'
          && chatTerminal.disposition === 'COMPLETED'
          && chatTerminal.finalResponse.trim().toLowerCase() === 'quokka'
          && Number.isSafeInteger(chatTerminal.totalTokensUsed)
          && chatTerminal.totalTokensUsed > 0,
      `recovered replacement child did not answer a real chat: HTTP ${chatResponse.status} ${chatResponse.text}`);
      terminal = { row: terminal, replacement, replacementVerified, chat: {
        status: chatResponse.status, terminal: chatTerminal,
      } };
    } else {
      accepted = await recoverAfterSlotCleanup(apiPort, manifest, request, 15000);
      requireThat(accepted.status === 202 && accepted.body?.component === 'generative'
          && accepted.body?.recovery === 'ACCEPTED',
      `second generative recovery attempt was not accepted: ${JSON.stringify(accepted)}`);
      let failedTwoLast = null;
      let failedTwoLastMatching = null;
      try {
        terminal = await waitFor('generative recovery attempt 2 failure', 60000, async () => {
          const row = await statusRow(apiPort, request, 'generative');
          failedTwoLast = { observedAt: new Date().toISOString(), row };
          if (row?.state === 'FAILED' && row.recoveryAttempts === 2) {
            failedTwoLastMatching = failedTwoLast;
          }
          return missingPrivateExecutableFailure(row, 2, privateRuntime.executable) ? row : null;
        });
      } catch (error) {
        writeAttemptFailure(work, 2, privateRuntime.executable, failedTwoLast,
          failedTwoLastMatching, error);
        throw error;
      }
      const exhausted = await recoverAfterSlotCleanup(apiPort, manifest, request, 15000);
      requireThat(exhausted.status === 503
          && exhausted.body?.errorCode === 'WORKER_RECOVERY_EXHAUSTED',
        `spent optional generative recovery budget was not terminal: ${JSON.stringify(exhausted)}`);
      terminal = { ...terminal, exhausted };
    }
    const finalSupervisor = readJson(path.join(runtime, 'supervisor.v1.json'));
    const finalManifest = readJson(path.join(runtime, 'manifest.json'));
    requireThat(finalSupervisor?.pid === first.pid && finalSupervisor.instanceId === first.instanceId
        && finalSupervisor.incarnation === first.incarnation && finalSupervisor.restartCount === 0
        && finalManifest?.pid === first.pid && finalManifest.instanceId === first.instanceId,
    `optional generative recovery escalated the Engine: ${JSON.stringify({
      finalSupervisor, finalManifest,
    })}`);
    console.log(exhaustion ? 'GENERATIVE_RECOVERY_EXHAUSTION_PASS'
      : 'GENERATIVE_RECOVERY_SUCCESS_PASS', JSON.stringify({ first, child: ready.child, reached,
      activation, initialGenerative: ready.row, profile: ready.manifest.chat, held, busy, failedOne,
      accepted, terminal, finalSupervisor }));
  } finally {
    fs.writeFileSync(releaseFile, new Date().toISOString());
    if (executableHeld && fs.existsSync(heldExecutable)
        && !fs.existsSync(privateRuntime.executable)) {
      fs.renameSync(heldExecutable, privateRuntime.executable);
    }
  }
}

async function requestOnlineIntent(apiPort, manifest, request) {
  const token = manifest.head?.sessionToken;
  const headers = { 'content-type': 'application/json' };
  if (typeof token === 'string' && token.length > 0) headers[SESSION_HEADER] = token;
  const response = await request(apiPort, '/api/inference/mode', {
    method: 'POST', headers, body: JSON.stringify({ mode: 'online' }),
  }, 30000);
  let body;
  try { body = JSON.parse(response.text); } catch { body = null; }
  return { status: response.status, body };
}

async function statusRow(apiPort, request, component) {
  try {
    const response = await request(apiPort, '/api/status', {}, 15000);
    if (response.status !== 200) return null;
    return JSON.parse(response.text).readiness?.engineComponents?.[component] ?? null;
  } catch { return null; }
}

async function recover(apiPort, manifest, request) {
  const token = manifest.head?.sessionToken;
  const headers = { 'content-type': 'application/json' };
  if (typeof token === 'string' && token.length > 0) headers[SESSION_HEADER] = token;
  const response = await request(apiPort, '/api/engine/components/generative/recover', {
    method: 'POST', headers, body: '{}',
  }, 15000);
  let body;
  try { body = JSON.parse(response.text); } catch { body = null; }
  return { status: response.status, body };
}

async function recoverAfterSlotCleanup(apiPort, manifest, request, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const response = await recover(apiPort, manifest, request);
    if (response.status !== 429) return response;
    if (response.body?.errorCode !== 'ADMISSION_ENGINE_LIMIT') {
      throw new Error(`unexpected recovery admission refusal: ${JSON.stringify(response)}`);
    }
    if (Date.now() >= deadline) {
      throw new Error(`recovery slot did not clear within ${timeoutMs}ms: ${JSON.stringify(response)}`);
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
}

function missingPrivateExecutableFailure(row, attempts, executable) {
  const evidence = String(row?.evidence ?? '').toLowerCase();
  return row?.state === 'FAILED' && row.recoveryAttempts === attempts
    && evidence.includes('same-configuration recovery failed [executable_not_found]:')
    && evidence.includes('llama-server executable not found at')
    && evidence.includes(path.normalize(path.resolve(executable)).toLowerCase());
}

function writeAttemptFailure(work, attempts, executable, lastObservation,
    lastMatchingFailedObservation, error) {
  fs.writeFileSync(path.join(work, `generative-recovery-attempt-${attempts}-failure.json`),
    JSON.stringify({ expectedExecutable: path.normalize(path.resolve(executable)),
      lastObservation, lastMatchingFailedObservation,
      waitError: String(error?.stack ?? error) }, null, 2));
}

function fileTimeToEpochMs(value) {
  try { return Number(BigInt(value) / 10000n - 11644473600000n); }
  catch { return Number.NaN; }
}

function doneEvent(sse) {
  for (const frame of String(sse ?? '').split(/\r?\n\r?\n/)) {
    const lines = frame.split(/\r?\n/);
    if (!lines.some(line => line.trim() === 'event: done')) continue;
    const data = lines.filter(line => line.startsWith('data:'))
      .map(line => line.slice('data:'.length).trim()).join('\n');
    try { return JSON.parse(data); } catch { return null; }
  }
  return null;
}

function samePath(left, right) {
  return typeof left === 'string' && typeof right === 'string'
    && path.resolve(left).toLowerCase() === path.resolve(right).toLowerCase();
}
