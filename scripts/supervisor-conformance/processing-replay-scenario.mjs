import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import identity from '../dev/lib/process-identity.cjs';

function recordedPair(data, parentKey) {
  const database = new DatabaseSync(path.join(data, 'operations.db'), { readOnly: true });
  try {
    const parent = database.prepare('SELECT id, operation_key, state, attempts FROM operations WHERE operation_key = ?').get(parentKey);
    const children = database.prepare(`SELECT id, operation_key, state, attempts FROM operations
      WHERE kind = 'ingest' AND operation_ref IS NULL
      AND json_extract(identity_json, '$.mode') = 'ingest-child'
      AND json_extract(identity_json, '$.parentOperationKey') = ?`).all(parentKey);
    if (children.length > 1) throw new Error('single-file replay admitted more than one recorded child');
    return { parent, child: children[0] };
  } finally {
    database.close();
  }
}

export async function exerciseProcessingReplay(c) {
  const { work, data, first, manifest, apiPort, readJson, waitFor, request, post,
    requireThat, requireOperationSuccess, createOperationKey, matchingHit, jobStateFor } = c;
  const supervisorFile = path.join(data, 'runtime', 'supervisor.v1.json');
  const manifestFile = path.join(data, 'runtime', 'manifest.json');
  requireThat(first.pid === manifest.pid && first.instanceId === manifest.instanceId,
    'fixture may crash only the admitted Engine it launched');
  const originalTable = identity.readProcessTable();
  requireThat(originalTable.ok, 'initial process identity must be available');
  const originalProcess = originalTable.table.find(row => Number(row.ProcessId) === first.pid);
  requireThat(originalProcess?.CommandLine?.includes(data), 'Engine command must name this fixture data');
  const record = { pid: first.pid, creationFileTimeUtc: originalProcess.CreationFileTimeUtc,
    cmdlineFingerprint: originalProcess.CommandLine };
  const file = path.join(work, 'corpus', 'chaos-hang-processing.txt');
  const marker = 'processingreplayedmarker';
  fs.writeFileSync(file, `${marker} platypus durable replay\n`);
  const operationKey = createOperationKey();
  const accepted = await post(apiPort, '/api/knowledge/ingest', {
    paths: [file], idempotencyKey: operationKey,
  });
  const ingestReceipt = requireOperationSuccess(accepted, 'processing-replay ingest');
  requireThat(ingestReceipt.operationKey === operationKey,
    `processing-replay ingest changed its supplied key: ${accepted.text}`);
  const claimed = await waitFor('actual extraction holds the exact recorded PROCESSING revision', 30000, () => {
    const queue = jobStateFor(path.basename(file));
    const pair = recordedPair(data, operationKey);
    const entered = path.join(work, 'processing-entered');
    return queue?.state === 'PROCESSING' && pair.parent?.state === 'RUNNING' && pair.child?.state === 'RUNNING'
      && pair.parent.attempts === 1 && pair.child.attempts === 1
      && queue.scan_id === pair.child.operation_key
      && typeof queue.unit_revision === 'string' && queue.unit_revision.length > 0
      && path.resolve(queue.path).toLowerCase() === path.resolve(file).toLowerCase()
      && fs.existsSync(entered)
      && path.resolve(fs.readFileSync(entered, 'utf8')).toLowerCase() === path.resolve(file).toLowerCase()
      ? { ...queue, parent: pair.parent, child: pair.child } : null;
  });
  const before = readJson(supervisorFile);
  requireThat(before?.runId === first.runId && before.incarnation === first.incarnation
    && before.pid === first.pid && before.instanceId === first.instanceId,
  'only the original admitted Engine may receive the test crash');
  const verified = identity.verifyProcessIdentity({ record, table: identity.readProcessTable() });
  requireThat(identity.isVerifiedMatch(verified), `refusing unverified test crash: ${verified.reason}`);
  // This is the regression's deliberate crash of its own Engine, not helper cleanup. The runner
  // remains alive and performs production recovery; final cleanup still uses its owned stop path.
  process.kill(first.pid, 'SIGKILL');
  await waitFor('Engine is dead and host has entered counted restart cooldown', 10000, () => {
    let alive = true;
    try { process.kill(first.pid, 0); } catch (error) {
      if (error.code === 'ESRCH') alive = false;
      else throw error;
    }
    const supervisor = readJson(supervisorFile);
    return !alive && supervisor?.runId === first.runId && supervisor.state === 'restarting'
      && supervisor.incarnation === first.incarnation
      && supervisor.restartCount === 1 && supervisor.lastExit?.counted === true;
  });
  const afterDeath = jobStateFor(path.basename(file));
  requireThat(afterDeath?.state === 'PROCESSING' && afterDeath.scan_id === claimed.scan_id
    && afterDeath.unit_revision === claimed.unit_revision && afterDeath.path === claimed.path,
    `must remain PROCESSING after actual death, not just earlier: ${JSON.stringify(afterDeath)}`);
  const stoppedPair = recordedPair(data, operationKey);
  requireThat(JSON.stringify(stoppedPair) === JSON.stringify({ parent: claimed.parent, child: claimed.child }),
    `the same running attempts must survive the crash: ${JSON.stringify(stoppedPair)}`);
  if (c.observeAfterDeath) await c.observeAfterDeath();
  const afterRead = readJson(supervisorFile);
  requireThat(afterRead?.runId === first.runId && afterRead.state === 'restarting'
    && afterRead.incarnation === first.incarnation,
  'successor must not have reclaimed the row before the post-death observation');
  console.log('PROCESSING_AFTER_DEATH', JSON.stringify(afterDeath));
  const argFile = process.env.JUSTSEARCH_PROCESSING_TEST_ARGFILE;
  requireThat(argFile && path.dirname(path.resolve(argFile)) === path.resolve(work),
    'successor parser override must be the fixture-owned argfile');
  const oldMain = 'io.justsearch.indexerworker.fixtures.ChaosExtractionSandboxChild';
  const args = fs.readFileSync(argFile, 'utf8');
  requireThat(args.includes(oldMain), 'the first Engine must have used the chaos parser');
  fs.writeFileSync(argFile, args.replace(oldMain, 'io.justsearch.indexerworker.extract.ExtractionSandboxChild'));
  const successor = await waitFor('successor admits and replays the orphaned PROCESSING job', 120000, async () => {
    const s = readJson(supervisorFile);
    const m = readJson(manifestFile);
    if (s?.state !== 'running' || s.incarnation !== first.incarnation + 1
      || s.runId !== first.runId || m?.instanceId !== s.instanceId || m?.pid !== s.pid
      || s.instanceId === first.instanceId || jobStateFor(path.basename(file))?.state !== 'DONE') return null;
    try {
      const result = await post(m.head.apiPort, '/api/knowledge/search', { query: marker, limit: 5, mode: 'text' });
      return matchingHit(result, file, marker) && (await request(m.head.apiPort, '/api/health')).status === 200
        ? { supervisor: s, manifest: m } : null;
    } catch { return null; }
  });
  requireThat(successor.supervisor.restartCount === 1, 'only the injected crash should spend budget');
  const replayed = jobStateFor(path.basename(file));
  requireThat(replayed?.scan_id === claimed.scan_id && replayed.unit_revision === claimed.unit_revision,
    `recovery must finish the original recorded revision: ${JSON.stringify({ claimed, replayed })}`);
  await waitFor('original recorded parent and child durably complete through one resume', 30000, () => {
    const pair = recordedPair(data, operationKey);
    return pair.parent?.state === 'COMPLETE' && pair.child?.state === 'COMPLETE'
      && pair.parent.id === claimed.parent.id && pair.child.id === claimed.child.id
      && pair.child.operation_key === claimed.scan_id
      && pair.parent.attempts === claimed.parent.attempts + 1
      && pair.child.attempts === claimed.child.attempts + 1;
  });
  const recoveredLog = fs.readFileSync(path.join(data, 'logs', 'engine.log'), 'utf8');
  requireThat(/Recovered [1-9][0-9]* unowned processing jobs/.test(recoveredLog), 'startup must report actual PROCESSING recovery');
  console.log('PROCESSING_REPLAY_PASS', JSON.stringify({ afterDeath,
    successor: successor.supervisor, final: jobStateFor(path.basename(file)) }));
  return { file, marker, successor, afterDeath, ingestReceipt };
}
