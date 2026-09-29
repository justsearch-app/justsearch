import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';
import identity from '../dev/lib/process-identity.cjs';
import { barrierFiles } from './barrier-files.mjs';

/** Installed query-only settings and subsequent boot use the same exact settings witness. */
export async function exerciseQueryRoleScenario(c) {
  const { scenario, work, data, modelsRoot, apiPort, manifest, first, readJson,
    request, post, waitFor, requireThat, createOperationKey, operationKey: faultKey } = c;
  const source = path.join(modelsRoot, 'onnx', 'citation-scorer');
  const selected = path.join(work, 'query-citation-b');
  const settingsPath = path.join(data, 'ui', 'settings.json');
  const hash = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const hashLargeFile = file => new Promise((resolve, reject) => {
    const digest = crypto.createHash('sha256');
    const input = fs.createReadStream(file);
    input.on('error', reject);
    input.on('data', chunk => digest.update(chunk));
    input.on('end', () => resolve(digest.digest('hex')));
  });
  const witnessFile = value => value?.startsWith('file:') ? fileURLToPath(value) : value;
  const stageCitationModel = (destination, modelSource = source) => {
    fs.mkdirSync(destination, { recursive: true });
    for (const name of ['model.onnx', 'tokenizer.json']) {
      const target = path.join(destination, name);
      if (!fs.existsSync(target)) {
        if (name === 'tokenizer.json') fs.copyFileSync(path.join(modelSource, name), target);
        else fs.linkSync(path.join(modelSource, name), target);
      }
    }
    const manifestFile = path.join(modelSource, 'model_manifest.json');
    if (fs.existsSync(manifestFile)) {
      fs.copyFileSync(manifestFile, path.join(destination, 'model_manifest.json'));
    }
  };
  const read = (response, label) => {
    try { return JSON.parse(response.text); }
    catch { throw new Error(`${label} returned invalid JSON: ${response.text}`); }
  };
  const status = async (port = apiPort) => {
    const response = await request(port, '/api/status', {}, 15000);
    requireThat(response.status === 200, `query-role status failed: ${response.text}`);
    return read(response, 'query-role status');
  };
  const modelQuery = async (label, port = apiPort) => {
    const response = await post(port, '/api/knowledge/match-citations', {
      answer_text: 'The quokka lives in Western Australia.',
      chunk_refs: [{ parent_doc_id: 'query-role-fixture', chunk_index: 0,
        passage_text: 'Quokkas live in Western Australia, including Rottnest Island.' }],
    }, 30000);
    const value = read(response, label);
    requireThat(response.status === 200 && value.ok === true
      && value.scorer === 'CROSS_ENCODER' && value.sentences_scored > 0,
    `${label} did not execute the citation model: HTTP ${response.status} ${response.text}`);
    return value;
  };
  const initial = await waitFor('query-role initial Worker owner', 90000, async () => {
    try {
      const value = await status();
      const encoders = value?.readiness?.engineComponents?.encoders;
      const expected = scenario === 'query-role-tampered-boot' || scenario === 'query-role-clear'
        || scenario === 'query-role-invalid-override-boot'
        ? 'UNAVAILABLE' : scenario === 'query-role-disabled-boot' ? 'ABSENT' : 'READY';
      return value?.worker && encoders?.state === expected
        && (expected !== 'READY' || typeof encoders.appliedVersion === 'string') ? value : null;
    } catch { return null; }
  });
  const initialVersion = initial.readiness.engineComponents.encoders.appliedVersion;
  const beforeResponse = await request(apiPort, '/api/settings/v2');
  requireThat(beforeResponse.status === 200, `query-role settings read failed: ${beforeResponse.text}`);
  const before = read(beforeResponse, 'query-role settings');
  requireThat(before.settingsMode === 'read_write', 'query-role fixture requires durable settings');

  if (scenario === 'query-role-installer-commit') {
    const token = manifest.head?.sessionToken;
    const headers = typeof token === 'string' && token.length > 0
      ? { 'X-JustSearch-Session': token } : {};
    for (const packageId of ['reranker', 'ner', 'splade']) {
      const declined = await request(apiPort, `/api/ai/install/packages/${packageId}/decline`,
        { method: 'POST', headers }, 30000);
      requireThat(declined.status === 200,
        `query-only installer could not decline ${packageId}: ${declined.text}`);
    }
    const previewResponse = await request(apiPort, '/api/ai/install/plan-preview');
    requireThat(previewResponse.status === 200,
      `query-only installer plan failed: ${previewResponse.text}`);
    const preview = read(previewResponse, 'query-only installer plan');
    const component = id => preview.components?.find(item => item.id === id);
    requireThat(preview.totalDownloadBytes === 0
      && component('embedding')?.state === 'installed'
      && component('citation-scorer')?.state === 'installed'
      && component('reranker')?.state === 'declined',
    `query-only installer plan would download or skip citation: ${previewResponse.text}`);
    const acceptedBeforeResponse = await request(apiPort, '/api/settings/v2');
    requireThat(acceptedBeforeResponse.status === 200,
      `query-only installer settings read failed: ${acceptedBeforeResponse.text}`);
    const acceptedBefore = read(acceptedBeforeResponse,
      'query-only installer settings before acquisition');
    const started = await request(apiPort, '/api/ai/install/start', {
      method: 'POST', headers: { ...headers, 'content-type': 'application/json' },
      body: JSON.stringify({ acceptTerms: true }),
    }, 30000);
    requireThat(started.status === 200,
      `query-only installer start failed: ${started.text}`);
    const completed = await waitFor('query-only installed acquisition', 180000, async () => {
      let response;
      try { response = await request(apiPort, '/api/ai/install/status', {}, 15000); }
      catch { return null; }
      requireThat(response.status === 200, `query-only installer status failed: ${response.text}`);
      const value = read(response, 'query-only installer status');
      if (value.state === 'failed' || value.state === 'cancelled') {
        throw new Error(`query-only installer failed: ${response.text}`);
      }
      return value.state === 'completed' ? value : null;
    });
    const selectedModel = readJson(path.join(work, 'query-installer-targets.json'))?.citationModel;
    requireThat(typeof selectedModel === 'string'
      && selectedModel.startsWith(path.join(work, 'installer-models') + path.sep),
    'query-only installer target is outside the private model root');
    const selectedTokenizer = path.join(path.dirname(selectedModel), 'tokenizer.json');
    const persisted = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    requireThat(persisted.acceptedRevision === acceptedBefore.witness.acceptedRevision + 1
      && persisted.queryRoles?.citation?.state === 'SELECTED'
      && witnessFile(persisted.queryRoles.citation.model.path) === selectedModel
      && persisted.queryRoles.citation.model.sha256 === hash(selectedModel)
      && witnessFile(persisted.queryRoles.citation.tokenizer.path) === selectedTokenizer
      && persisted.queryRoles.citation.tokenizer.sha256 === hash(selectedTokenizer),
    `installer did not commit exact citation identity: ${JSON.stringify(persisted)}`);
    const contract = JSON.parse(fs.readFileSync(path.join(data, 'install-contract.v2.json'), 'utf8'));
    requireThat(contract.models?.['citation-scorer']?.skipped === false
      && path.join(witnessFile(contract.modelsDir), contract.models['citation-scorer'].targetDir,
        contract.models['citation-scorer'].variantFilename) === selectedModel
      && contract.models?.embedding?.skipped === false
      && contract.models?.reranker?.skipped === true,
    `ordinary installer contract does not match query-only acquisition: ${JSON.stringify(contract)}`);
    const ready = await waitFor('query-only installed citation owner', 90000, async () => {
      try {
        const value = await status();
        return value.readiness.engineComponents.encoders.state === 'READY' ? value : null;
      } catch { return null; }
    });
    const scored = await modelQuery('query-only installed citation');
    console.log('QUERY_ROLE_INSTALLER_COMMIT_PASS', JSON.stringify({
      state: completed.state, witness: persisted.acceptedRevision,
      model: selectedModel, modelSha256: persisted.queryRoles.citation.model.sha256,
      tokenizerSha256: persisted.queryRoles.citation.tokenizer.sha256,
      scorer: scored.scorer,
      appliedVersion: ready.readiness.engineComponents.encoders.appliedVersion }));
    return;
  }

  if (scenario === 'query-role-installer-boot') {
    const selectedModel = readJson(path.join(work, 'query-installer-targets.json'))?.citationModel;
    requireThat(typeof selectedModel === 'string',
      'query-only installer reboot lacks its private expected target');
    const selectedTokenizer = path.join(path.dirname(selectedModel), 'tokenizer.json');
    const persisted = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    requireThat(persisted.queryRoles?.citation?.state === 'SELECTED'
      && witnessFile(persisted.queryRoles.citation.model.path) === selectedModel
      && persisted.queryRoles.citation.model.sha256 === hash(selectedModel)
      && witnessFile(persisted.queryRoles.citation.tokenizer.path) === selectedTokenizer
      && persisted.queryRoles.citation.tokenizer.sha256 === hash(selectedTokenizer)
      && before.witness.acceptedRevision === persisted.acceptedRevision,
    'installed query-only citation witness changed on reboot');
    const scored = await modelQuery('query-only installer reboot citation');
    console.log('QUERY_ROLE_INSTALLER_BOOT_PASS', JSON.stringify({
      witness: before.witness, model: selectedModel, scorer: scored.scorer,
      appliedVersion: initialVersion }));
    return;
  }

  if (scenario === 'query-role-contract-b-settings-a') {
    const committed = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    const selectedA = committed.queryRoles?.citation?.model?.path;
    requireThat(typeof selectedA === 'string' && committed.queryRoles.citation.state === 'SELECTED',
      'contract B crash requires a prior committed query A witness');
    await modelQuery('query A before contract publication');
    const modelDir = path.join(work, 'query-citation-c');
    fs.mkdirSync(modelDir, { recursive: true });
    for (const name of ['model.onnx', 'tokenizer.json']) {
      const target = path.join(modelDir, name);
      const original = path.join(source, name);
      if (!fs.existsSync(target)) {
        if (name === 'tokenizer.json') fs.copyFileSync(original, target);
        else fs.linkSync(original, target);
      }
    }
    const contract = {
      schemaVersion: 2, installedAtEpochMs: Date.now(),
      hardwareProfile: { gpuDetected: false, cudaFunctional: false, vramBytes: -1 },
      downloadProfile: 'CPU', modelsDir: work,
      models: { 'citation-scorer': {
        packageId: 'citation-scorer', variantFilename: 'model.onnx',
        precision: 'INT8', targetEP: 'CPU', targetDir: 'query-citation-c',
        sha256: hash(path.join(modelDir, 'model.onnx')),
        installedFiles: ['model.onnx', 'tokenizer.json'], skipped: false,
      } },
    };
    const contractPath = path.join(data, 'install-contract.v2.json');
    const pendingPath = `${contractPath}.pending`;
    const contractPublishedAtMs = Date.now();
    fs.writeFileSync(pendingPath, `${JSON.stringify(contract, null, 2)}\n`);
    fs.renameSync(pendingPath, contractPath);
    requireThat(JSON.stringify(JSON.parse(fs.readFileSync(settingsPath, 'utf8')))
      === JSON.stringify(committed),
    'contract B publication changed the settings A witness');
    const table = identity.readProcessTable();
    requireThat(table.ok, 'contract B crash cannot verify Engine process identity');
    const original = table.table.find(row => Number(row.ProcessId) === first.pid);
    requireThat(original?.CommandLine?.includes(data)
      && first.pid === manifest.pid && first.instanceId === manifest.instanceId,
    'contract B crash may kill only the admitted Engine');
    const record = { pid: first.pid, creationFileTimeUtc: original.CreationFileTimeUtc,
      cmdlineFingerprint: original.CommandLine };
    const current = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
    requireThat(current?.pid === first.pid && current.instanceId === first.instanceId,
      'contract B was published after Engine identity changed');
    const verified = identity.verifyProcessIdentity({ record,
      table: identity.readProcessTable() });
    requireThat(identity.isVerifiedMatch(verified),
      `refusing an unverified contract B crash: ${verified.reason}`);
    process.kill(first.pid, 'SIGKILL');
    const successor = await waitFor('contract B settings A successor', 150000, async () => {
      const s = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
      const m = readJson(path.join(data, 'runtime', 'manifest.json'));
      if (s?.state !== 'running' || s.runId !== first.runId
        || s.incarnation !== first.incarnation + 1 || s.instanceId === first.instanceId
        || m?.pid !== s.pid || m?.instanceId !== s.instanceId) return null;
      try {
        const health = await request(m.head.apiPort, '/api/health', {}, 10000);
        return health.status === 200 ? { supervisor: s, manifest: m } : null;
      } catch { return null; }
    });
    requireThat(successor.supervisor.restartCount === 1,
      'contract B crash spent an unexpected restart budget');
    const ready = await waitFor('query A after contract B crash', 90000, async () => {
      const value = await status(successor.manifest.head.apiPort);
      return value.readiness.engineComponents.encoders.state === 'READY' ? value : null;
    });
    const scored = await modelQuery('query A after contract B crash',
      successor.manifest.head.apiPort);
    const restored = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    requireThat(JSON.stringify(restored) === JSON.stringify(committed)
      && restored.queryRoles.citation.model.path === selectedA,
    'contract B crash changed the committed query A selection');
    console.log('QUERY_ROLE_CONTRACT_B_SETTINGS_A_PASS', JSON.stringify({
      selectedA, contractB: path.join(modelDir, 'model.onnx'),
      contractPublishedAtMs,
      witness: before.witness, runId: successor.supervisor.runId,
      restartCount: successor.supervisor.restartCount,
      scorer: scored.scorer,
      appliedVersion: ready.readiness.engineComponents.encoders.appliedVersion }));
    return;
  }

  if (scenario === 'query-role-clear') {
    const headers = { 'content-type': 'application/json' };
    const token = manifest.head?.sessionToken;
    if (typeof token === 'string' && token.length > 0) headers['X-JustSearch-Session'] = token;
    const operationKey = createOperationKey();
    const response = await request(apiPort, '/api/settings/v2', {
      method: 'POST', headers,
      body: JSON.stringify({ citationScorerModelPath: '', witness: before.witness, operationKey }),
    }, 180000);
    const result = read(response, 'query-role clear');
    requireThat(response.status === 200 && result.state === 'COMPLETE',
      `query-role clear refused: HTTP ${response.status} ${response.text}`);
    const persisted = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    requireThat(persisted.queryRoles?.citation?.state === 'DISABLED'
      && persisted.acceptedRevision === before.witness.acceptedRevision + 1,
    `query-role clear did not commit explicit disablement: ${JSON.stringify(persisted)}`);
    const after = await status();
    requireThat(after.readiness.engineComponents.encoders.state === 'ABSENT',
      `query-role clear did not publish absence: ${JSON.stringify(after.readiness.engineComponents.encoders)}`);
    console.log('QUERY_ROLE_CLEAR_PASS', JSON.stringify({ operationKey,
      witness: result.witness, encoders: after.readiness.engineComponents.encoders }));
    return;
  }

  if (scenario === 'query-role-disabled-boot') {
    const persisted = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    requireThat(persisted.queryRoles?.citation?.state === 'DISABLED'
      && before.witness.acceptedRevision === persisted.acceptedRevision,
    'query-role disabled boot lost its committed explicit disablement');
    const response = await post(apiPort, '/api/knowledge/match-citations', {
      answer_text: 'The quokka lives in Western Australia.',
      chunk_refs: [{ parent_doc_id: 'query-role-fixture', chunk_index: 0,
        passage_text: 'Quokkas live in Western Australia.' }],
    }, 30000);
    const fallback = read(response, 'disabled citation query');
    requireThat(response.status === 200 && fallback.ok === true
      && fallback.scorer !== 'CROSS_ENCODER',
    `explicitly disabled citation still executed: ${response.text}`);
    console.log('QUERY_ROLE_DISABLED_BOOT_PASS', JSON.stringify({
      witness: before.witness, scorer: fallback.scorer }));
    return;
  }

  if (scenario === 'query-role-override-boot') {
    const persisted = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    requireThat(persisted.queryRoles?.citation?.state === 'DISABLED'
      && before.witness.acceptedRevision === persisted.acceptedRevision,
    'operator override changed the committed disabled citation witness');
    requireThat(initial.readiness.engineComponents.encoders.state === 'READY',
      `operator override did not publish a ready query role: ${JSON.stringify(initial)}`);
    const query = await modelQuery('operator-overridden citation');
    console.log('QUERY_ROLE_OVERRIDE_BOOT_PASS', JSON.stringify({
      witness: before.witness, scorer: query.scorer,
      sentencesScored: query.sentences_scored,
      appliedVersion: initialVersion }));
    return;
  }

  if (scenario === 'query-role-invalid-override-boot') {
    const persisted = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    requireThat(persisted.queryRoles?.citation?.state === 'DISABLED'
      && before.witness.acceptedRevision === persisted.acceptedRevision,
    'invalid operator override changed the committed citation witness');
    requireThat(initial.worker && initial.readiness.engineComponents.encoders.state === 'UNAVAILABLE'
      && initial.readiness.engineComponents.encoders.evidence?.includes('CITATION'),
    `invalid override did not leave a running Worker with citation evidence: ${JSON.stringify(initial)}`);
    const response = await post(apiPort, '/api/knowledge/match-citations', {
      answer_text: 'The quokka lives in Western Australia.',
      chunk_refs: [{ parent_doc_id: 'query-role-fixture', chunk_index: 0,
        passage_text: 'Quokkas live in Western Australia.' }],
    }, 30000);
    const fallback = read(response, 'invalid override citation query');
    requireThat(response.status === 200 && fallback.ok === true
      && fallback.scorer !== 'CROSS_ENCODER',
    `invalid operator override still executed a citation model: ${response.text}`);
    console.log('QUERY_ROLE_INVALID_OVERRIDE_BOOT_PASS', JSON.stringify({
      witness: before.witness, encoders: initial.readiness.engineComponents.encoders,
      scorer: fallback.scorer }));
    return;
  }

  if (scenario === 'query-role-commit' || scenario === 'query-role-after-file-crash'
      || scenario === 'query-role-issued-a' || scenario === 'query-role-publication-hold'
      || scenario === 'query-role-two-owner-rollback') {
    const stagedSource = scenario === 'query-role-two-owner-rollback'
      ? path.join(work, 'query-two-owner-generative-models', 'onnx', 'citation-scorer')
      : source;
    stageCitationModel(selected, stagedSource);
    const operationKey = faultKey ?? createOperationKey();
    const headers = { 'content-type': 'application/json' };
    const token = manifest.head?.sessionToken;
    if (typeof token === 'string' && token.length > 0) {
      headers['X-JustSearch-Session'] = token;
    }
    if (scenario === 'query-role-two-owner-rollback') {
      let servingBefore = before;
      if (!fs.existsSync(settingsPath)) {
        const seedKey = createOperationKey();
        const seededResponse = await request(apiPort, '/api/settings/v2', {
          method: 'POST', headers, body: JSON.stringify({
            ui: { chatEnabled: false }, witness: servingBefore.witness, operationKey: seedKey,
          }),
        }, 30000);
        const seeded = read(seededResponse, 'two-owner settings A seed');
        requireThat(seededResponse.status === 200 && seeded.state === 'COMPLETE'
            && seeded.operationKey === seedKey && fs.existsSync(settingsPath),
        `settings owner did not materialize A: HTTP ${seededResponse.status} ${seededResponse.text}`);
        const servingBeforeResponse = await request(apiPort, '/api/settings/v2');
        requireThat(servingBeforeResponse.status === 200,
          `two-owner seeded settings read failed: ${servingBeforeResponse.text}`);
        servingBefore = read(servingBeforeResponse, 'two-owner seeded settings A');
        requireThat(JSON.stringify(servingBefore.witness) === JSON.stringify(seeded.witness),
          `settings owner seed witness drifted: ${servingBeforeResponse.text}`);
      }
      const settingsBytes = fs.readFileSync(settingsPath);
      const statusA = await status();
      const initialComponents = statusA.readiness.engineComponents;
      requireThat(typeof initialComponents.generative?.appliedVersion === 'string'
          && initialComponents.generative.appliedVersion.length > 0,
        `two-owner fixture lacks applied generative A: ${JSON.stringify(initialComponents)}`);
      const effectiveBeforeResponse = await request(apiPort, '/api/debug/effective-config');
      requireThat(effectiveBeforeResponse.status === 200,
        `two-owner effective config read failed: ${effectiveBeforeResponse.text}`);
      const effectiveBefore = read(effectiveBeforeResponse, 'two-owner effective config');
      const resolved = key => effectiveBefore.resolvedConfig?.find(entry => entry.key === key);
      const effective = key => effectiveBefore.keys?.find(entry => entry.key === key);
      const serverExecutable = effective('justsearch.server.exe')?.value;
      const modelsDir = effective('justsearch.models.dir')?.value;
      const chatProfile = resolved('justsearch.chat.profile');
      const legacyChatProfile = resolved('justsearch.vlm.profile');
      const llmModelOverride = resolved('justsearch.llm.model_path');
      const vlmModelOverride = resolved('justsearch.vlm.model');
      requireThat(typeof serverExecutable === 'string' && fs.existsSync(serverExecutable)
          && fs.statSync(serverExecutable).isFile(),
      `two-owner fixture lacks its managed llama-server executable: ${effectiveBeforeResponse.text}`);
      requireThat(chatProfile?.value === 'compact' && chatProfile.source === 'env_var'
          && chatProfile.ordinal === 400 && chatProfile.detail === 'JUSTSEARCH_CHAT_PROFILE'
          && (legacyChatProfile == null || legacyChatProfile.value == null
            || legacyChatProfile.value.trim() === '')
          && (llmModelOverride == null || llmModelOverride.value == null
            || llmModelOverride.value.trim() === '')
          && (vlmModelOverride == null || vlmModelOverride.value == null
            || vlmModelOverride.value.trim() === ''),
        `compact profile is not the sole generative model owner: ${JSON.stringify({
          chatProfile, legacyChatProfile, llmModelOverride, vlmModelOverride })}`);
      requireThat(typeof modelsDir === 'string' && modelsDir.length > 0,
        `two-owner fixture has no effective models directory: ${JSON.stringify({
          chatProfile, legacyChatProfile })}`);
      const citationA = path.join(modelsDir, 'onnx', 'citation-scorer');
      const citationAModel = path.join(citationA, 'model.onnx');
      const citationBModel = path.join(selected, 'model.onnx');
      requireThat(fs.existsSync(path.join(citationA, 'model.onnx'))
          && fs.existsSync(path.join(citationA, 'tokenizer.json'))
          && path.resolve(citationA) !== path.resolve(selected),
        `citation A and candidate B identities are not distinct and complete: ${JSON.stringify({
          citationA, citationB: selected })}`);
      const citationASha = hash(citationAModel);
      const citationBSha = hash(citationBModel);
      const citationBTokenizerSha = hash(path.join(selected, 'tokenizer.json'));
      requireThat(citationASha === citationBSha,
        `candidate B was not staged from private citation A: ${JSON.stringify({
          citationASha, citationBSha })}`);
      const configStoreA = JSON.stringify(effectiveBefore.resolvedConfig);
      const absentGguf = path.join(modelsDir, 'compact', 'Qwen3.5-4B-Q4_K_M.gguf');
      const retainedCompact = path.join(modelsRoot, 'compact', 'Qwen3.5-4B-Q4_K_M.gguf');
      const retainedBefore = fs.statSync(retainedCompact);
      const retainedCompactShaBefore = await hashLargeFile(retainedCompact);
      requireThat(fs.existsSync(absentGguf)
          && path.resolve(absentGguf) !== path.resolve(retainedCompact),
        `A seed did not use its owned compact-model link: ${JSON.stringify({
          absentGguf, retainedCompact })}`);
      fs.unlinkSync(absentGguf);
      const retainedAfter = fs.statSync(retainedCompact);
      requireThat(!fs.existsSync(absentGguf) && fs.existsSync(retainedCompact)
          && retainedAfter.size === retainedBefore.size
          && retainedAfter.mtimeMs === retainedBefore.mtimeMs,
        `owned compact unlink changed the retained source: ${JSON.stringify({
          absentGguf, retainedCompact, retainedBefore, retainedAfter })}`);
      const profileModelAbsent = !fs.existsSync(absentGguf);
      await modelQuery('query A before two-owner reconfigure');
      const lexicalBefore = await post(apiPort, '/api/knowledge/search',
        { query: 'quokka', limit: 5, mode: 'text' }, 15000);
      requireThat(lexicalBefore.status === 200,
        `query A search failed before two-owner reconfigure: ${lexicalBefore.text}`);

      const input = {
        ui: { chatEnabled: true },
        citationScorerModelPath: selected,
        witness: servingBefore.witness,
        operationKey,
      };
      const engineLogPath = path.join(data, 'logs', 'engine.log');
      const engineLogOffset = fs.statSync(engineLogPath).size;
      const logEvents = () => fs.readFileSync(engineLogPath).subarray(engineLogOffset)
        .toString('utf8').split(/\r?\n/).filter(Boolean).flatMap(line => {
          try { return [JSON.parse(line)]; } catch { return []; }
        });
      const selectedMessage = `Citation scorer settings selected: model=${citationBModel}, `
        + `sha256=${citationBSha}, tokenizerSha256=${citationBTokenizerSha}`;
      const scorerInit = event => event.logger_name === 'io.justsearch.reranker.CitationScorer'
        && event.message?.startsWith('CitationScorer initialized (CPU-only):');
      const scorerClose = event => event.logger_name === 'io.justsearch.reranker.CitationScorer'
        && event.message === 'CitationScorer closed.';
      const bSelection = event => event.logger_name === 'i.j.i.server.InferenceCompositionRoot'
        && event.message === selectedMessage;
      const pending = request(apiPort, '/api/settings/v2', {
        method: 'POST', headers, body: JSON.stringify(input),
      }, 180000);
      const { reachedFile, releaseFile } = barrierFiles(data);
      const reached = await waitFor('two-owner encoder preparation barrier', 120000, () =>
        readJson(reachedFile));
      requireThat(reached?.phase === 'settings-mid-compose'
          && reached.parentKind === 'reconfigure'
          && reached.parentKey === operationKey && reached.operationKey === operationKey
          && reached.cursor === 'encoders' && reached.pid === first.pid
          && reached.operationRecordId > 0,
      `two-owner barrier did not name the exact encoder candidate: ${JSON.stringify(reached)}`);
      requireThat(first.pid === manifest.pid && first.instanceId === manifest.instanceId,
        'two-owner barrier escaped the admitted Engine identity');
      const preparedLog = await waitFor('exact citation B preparation log', 30000, () => {
        const events = logEvents();
        const selectedAt = events.findIndex(bSelection);
        const initializedAt = events.findIndex(scorerInit);
        return selectedAt >= 0 && initializedAt > selectedAt
          ? { events, selectedAt, initializedAt } : null;
      });
      requireThat(preparedLog.events.filter(bSelection).length === 1
          && preparedLog.events.filter(scorerInit).length === 1
          && preparedLog.events.filter(scorerClose).length === 0
          && preparedLog.events[preparedLog.selectedAt].thread_name
            === preparedLog.events[preparedLog.initializedAt].thread_name,
      `barrier did not follow one exact B initialization: ${JSON.stringify(preparedLog.events)}`);
      fs.writeFileSync(releaseFile, `${Date.now()}\n`);
      const response = await pending;
      const result = read(response, 'two-owner generative refusal');
      requireThat(response.status >= 400 && result.state === 'FAILED'
          && result.operationKey === operationKey
          && result.operationRecordId === reached.operationRecordId
          && result.errorCode === 'GENERATIVE_PREPARATION_REFUSED',
      `absent GGUF did not fail through the generative owner: HTTP ${response.status} ${response.text}`);
      const requestLog = await waitFor('candidate B close after exact generative refusal', 30000, () => {
        const events = logEvents();
        const selectedAt = events.findIndex(bSelection);
        const initializedAt = events.findIndex(scorerInit);
        const missingAt = events.findIndex(event => event.message
          === `    Model path: ${absentGguf} (exists: false)`);
        const governedAt = events.findIndex(event => event.message?.startsWith(
          `Chat model governed by profile:compact: model=${absentGguf} `));
        const closedAt = events.findIndex(scorerClose);
        return selectedAt >= 0 && initializedAt > selectedAt && missingAt > initializedAt
          && governedAt > missingAt && closedAt > governedAt
          ? { events, selectedAt, initializedAt, missingAt, governedAt, closedAt } : null;
      });
      const requestThread = requestLog.events[requestLog.selectedAt].thread_name;
      requireThat(requestLog.events.filter(bSelection).length === 1
          && requestLog.events.filter(scorerInit).length === 1
          && requestLog.events.filter(scorerClose).length === 1
          && [requestLog.initializedAt, requestLog.missingAt, requestLog.governedAt,
            requestLog.closedAt].every(index => requestLog.events[index].thread_name === requestThread),
      `one request did not prepare B, refuse the exact compact path, and close B: ${JSON.stringify(
        requestLog.events)}`);

      const operationPath = path.join(data, 'operations.db');
      const failed = operationRow(operationPath, operationKey);
      requireThat(failed?.state === 'FAILED'
          && failed.id === reached.operationRecordId
          && failed.failure_reason === 'GENERATIVE_PREPARATION_REFUSED',
      `two-owner row did not name the generative failure: ${JSON.stringify(failed)}`);
      const settingsAfter = await request(apiPort, '/api/settings/v2');
      const restored = read(settingsAfter, 'two-owner restored settings');
      const statusAfter = await status();
      const effectiveAfterResponse = await request(apiPort, '/api/debug/effective-config');
      const effectiveAfter = read(effectiveAfterResponse, 'two-owner restored effective config');
      const settingsUnchanged = settingsAfter.status === 200
        && Buffer.compare(settingsBytes, fs.readFileSync(settingsPath)) === 0
        && JSON.stringify(restored.witness) === JSON.stringify(servingBefore.witness);
      requireThat(settingsUnchanged,
      `failed two-owner candidate changed settings A: ${settingsAfter.text}`);
      const configStoreUnchanged = effectiveAfterResponse.status === 200
        && JSON.stringify(effectiveAfter.resolvedConfig) === configStoreA;
      requireThat(configStoreUnchanged,
      `failed two-owner candidate changed ConfigStore A: ${effectiveAfterResponse.text}`);
      requireThat(statusAfter.readiness.engineComponents.encoders.appliedVersion
            === initialComponents.encoders.appliedVersion
          && statusAfter.readiness.engineComponents.generative.appliedVersion
            === initialComponents.generative.appliedVersion,
      `failed two-owner candidate changed applied component versions: ${JSON.stringify({
        before: initialComponents, after: statusAfter.readiness.engineComponents })}`);
      const query = await modelQuery('query A after two-owner rollback');
      const lexical = await post(apiPort, '/api/knowledge/search',
        { query: 'quokka', limit: 5, mode: 'text' }, 15000);
      requireThat(lexical.status === 200,
        `query A search failed after two-owner rollback: ${lexical.text}`);
      fs.rmSync(selected, { recursive: true });
      const queryCandidatePathReleased = !fs.existsSync(selected);
      requireThat(queryCandidatePathReleased,
        'aborted query candidate retained an owned model-path handle');
      stageCitationModel(selected, citationA);
      requireThat(hash(path.join(selected, 'model.onnx')) === citationBSha,
        'same-key retry B restage changed the candidate model identity');
      const { reachedFile: retryReachedFile, releaseFile: retryReleaseFile } = barrierFiles(data);
      fs.rmSync(retryReachedFile, { force: true });
      fs.rmSync(retryReleaseFile, { force: true });
      const retryLogBefore = logEvents();
      const retryCountsBefore = {
        prepare: retryLogBefore.filter(bSelection).length,
        initialize: retryLogBefore.filter(scorerInit).length,
        close: retryLogBefore.filter(scorerClose).length,
      };
      const stableRow = JSON.stringify(failed);
      const retry = await request(apiPort, '/api/settings/v2', {
        method: 'POST', headers, body: JSON.stringify(input),
      }, 30000);
      const retryResult = read(retry, 'two-owner same-key retry');
      requireThat(retry.status >= 400 && retryResult.state === 'FAILED'
          && retryResult.operationKey === operationKey
          && retryResult.operationRecordId === failed.id
          && retryResult.errorCode === 'GENERATIVE_PREPARATION_REFUSED'
          && JSON.stringify(operationRow(operationPath, operationKey)) === stableRow
          && Buffer.compare(settingsBytes, fs.readFileSync(settingsPath)) === 0,
      `same-key retry changed the failed candidate or settings A: ${retry.text}`);
      const retryLogAfter = logEvents();
      const retryCountsAfter = {
        prepare: retryLogAfter.filter(bSelection).length,
        initialize: retryLogAfter.filter(scorerInit).length,
        close: retryLogAfter.filter(scorerClose).length,
      };
      requireThat(!fs.existsSync(retryReachedFile)
          && JSON.stringify(retryCountsAfter) === JSON.stringify(retryCountsBefore),
        `same-key retry re-executed B preparation or its barrier: ${JSON.stringify({
          retryCountsBefore, retryCountsAfter, reached: readJson(retryReachedFile) })}`);
      const retainedCompactShaAfter = await hashLargeFile(retainedCompact);
      const retainedCompactUnchanged = fs.existsSync(retainedCompact)
        && retainedCompactShaAfter === retainedCompactShaBefore;
      const citationAShaAfter = hash(citationAModel);
      requireThat(retainedCompactUnchanged && citationAShaAfter === citationASha,
        `scenario changed retained compact or private citation A bytes: ${JSON.stringify({
          retainedCompactShaBefore, retainedCompactShaAfter, citationASha, citationAShaAfter })}`);
      const supervisor = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
      requireThat(supervisor?.pid === first.pid && supervisor.instanceId === first.instanceId
          && supervisor.incarnation === first.incarnation
          && supervisor.restartCount === first.restartCount,
      `two-owner rollback restarted the Engine: ${JSON.stringify(supervisor)}`);
      console.log('QUERY_ROLE_TWO_OWNER_ROLLBACK_PASS', JSON.stringify({
        operationKey, reached, failureReason: failed.failure_reason,
        absentGguf,
        queryCandidateAborted: requestLog.events.filter(scorerClose).length === 1,
        queryCandidatePathReleased,
        chatProfile: { value: chatProfile.value, source: chatProfile.source,
          ordinal: chatProfile.ordinal, model: absentGguf },
        profileModelAbsent,
        ownedProfileLinkRemoved: profileModelAbsent, retainedCompactUnchanged,
        candidateChatEnabled: input.ui.chatEnabled,
        citationAPath: citationA,
        candidateCitationModelPath: input.citationScorerModelPath,
        citationASha, citationBSha,
        citationBLog: {
          selected: requestLog.events[requestLog.selectedAt].message,
          initialized: requestLog.events[requestLog.initializedAt].message,
          closed: requestLog.events[requestLog.closedAt].message,
        },
        generativeFailureLog: {
          thread: requestThread,
          missingPath: requestLog.events[requestLog.missingAt].message,
          governed: requestLog.events[requestLog.governedAt].message,
        },
        retryCountsBefore, retryCountsAfter,
        retainedCompactShaBefore, retainedCompactShaAfter,
        witness: restored.witness, settingsUnchanged, configStoreUnchanged,
        encodersAppliedVersion: initialComponents.encoders.appliedVersion,
        generativeAppliedVersion: initialComponents.generative.appliedVersion,
        scorer: query.scorer, lexicalStatus: lexical.status,
        pid: supervisor.pid, restartCount: supervisor.restartCount,
        stableRetryRecordId: retryResult.operationRecordId,
      }));
      return;
    }
    let heldA = null;
    let heldSettled = false;
    if (scenario === 'query-role-issued-a') {
      heldA = post(apiPort, '/api/knowledge/match-citations', {
        answer_text: 'The quokka A request remains issued.',
        chunk_refs: [{ parent_doc_id: 'query-role-fixture', chunk_index: 0,
          passage_text: 'The quokka A request remains issued and grounded.' }],
      }, 120000);
      void heldA.then(() => { heldSettled = true; }, () => { heldSettled = true; });
      const reached = await waitFor('issued A citation after scorer capture', 30000, () =>
        readJson(path.join(data, 'runtime', 'issued-a-citation-reached.json')));
      requireThat(reached?.pid === first.pid
        && reached.answer === 'The quokka A request remains issued.',
      `issued citation marker did not capture A: ${JSON.stringify(reached)}`);
    }
    if (scenario === 'query-role-after-file-crash') {
      const table = identity.readProcessTable();
      requireThat(table.ok, 'query crash fixture cannot verify Engine process identity');
      const original = table.table.find(row => Number(row.ProcessId) === first.pid);
      requireThat(original?.CommandLine?.includes(data)
        && first.pid === manifest.pid && first.instanceId === manifest.instanceId,
      'query crash fixture may kill only its own admitted Engine');
      const record = { pid: first.pid, creationFileTimeUtc: original.CreationFileTimeUtc,
        cmdlineFingerprint: original.CommandLine };
      const held = fetch(`http://127.0.0.1:${apiPort}/api/settings/v2`, {
        method: 'POST', headers,
        body: JSON.stringify({ citationScorerModelPath: selected,
          witness: before.witness, operationKey }),
      }).then(response => response.text()).catch(error => String(error));
      const reached = await waitFor('query settings after-file fault', 120000, () =>
        readJson(path.join(data, 'runtime', 'operation-fault-reached.json')));
      requireThat(reached?.phase === 'settings-after-file-replace-before-publication'
        && reached.parentKey === operationKey && reached.pid === first.pid,
      `query settings fault marker mismatched: ${JSON.stringify(reached)}`);
      const persisted = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
      const citation = persisted.queryRoles?.citation;
      requireThat(persisted.acceptedRevision === before.witness.acceptedRevision + 1
        && persisted.lastCommittedOperationKey === operationKey
        && citation?.state === 'SELECTED'
        && citation.model.sha256 === hash(path.join(selected, 'model.onnx'))
        && citation.tokenizer.sha256 === hash(path.join(selected, 'tokenizer.json')),
      `file replacement did not durably select query B: ${JSON.stringify(persisted)}`);
      const current = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
      requireThat(current?.pid === first.pid && current.instanceId === first.instanceId,
        'query fault marker no longer belongs to the admitted Engine');
      const verified = identity.verifyProcessIdentity({ record,
        table: identity.readProcessTable() });
      requireThat(identity.isVerifiedMatch(verified),
        `refusing an unverified query fault: ${verified.reason}`);
      process.kill(first.pid, 'SIGKILL');
      const successor = await waitFor('query witness successor Engine', 150000, async () => {
        const s = readJson(path.join(data, 'runtime', 'supervisor.v1.json'));
        const m = readJson(path.join(data, 'runtime', 'manifest.json'));
        if (s?.state !== 'running' || s.runId !== first.runId
          || s.incarnation !== first.incarnation + 1 || s.instanceId === first.instanceId
          || m?.pid !== s.pid || m?.instanceId !== s.instanceId) return null;
        try {
          const health = await request(m.head.apiPort, '/api/health', {}, 10000);
          return health.status === 200 ? { supervisor: s, manifest: m } : null;
        } catch { return null; }
      });
      requireThat(successor.supervisor.restartCount === 1,
        'query crash spent an unexpected restart budget');
      const settled = await waitFor('query witness B after crash', 90000, async () => {
        const value = await status(successor.manifest.head.apiPort);
        return value?.readiness?.engineComponents?.encoders?.state === 'READY'
          ? value : null;
      });
      const query = await modelQuery('query B after file crash', successor.manifest.head.apiPort);
      const recovered = await request(successor.manifest.head.apiPort, '/api/settings/v2');
      requireThat(recovered.status === 200
        && read(recovered, 'recovered settings').witness.acceptedRevision
          === persisted.acceptedRevision,
      'successor changed the durable query settings revision');
      await held;
      console.log('QUERY_ROLE_AFTER_FILE_CRASH_PASS', JSON.stringify({
        reached, witness: recovered.text, scorer: query.scorer,
        appliedVersion: settled.readiness.engineComponents.encoders.appliedVersion,
        restartCount: successor.supervisor.restartCount }));
      return;
    }
    if (scenario === 'query-role-publication-hold') {
      const pendingCommit = request(apiPort, '/api/settings/v2', {
        method: 'POST', headers,
        body: JSON.stringify({ citationScorerModelPath: selected,
          witness: before.witness, operationKey }),
      }, 120000);
      const reached = await waitFor('query view assigned before registry batch', 60000, () =>
        readJson(path.join(data, 'runtime', 'query-publication-reached.json')));
      requireThat(reached?.pid === first.pid,
        `publication barrier belongs to another Engine: ${JSON.stringify(reached)}`);
      const persisted = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
      requireThat(persisted.acceptedRevision === before.witness.acceptedRevision + 1
        && persisted.queryRoles?.citation?.state === 'SELECTED',
      'publication barrier was reached before the durable B witness');
      let statusSettled = false;
      let querySettled = false;
      const capturedStatus = status().then(value => {
        statusSettled = true;
        return value;
      }, error => { statusSettled = true; throw error; });
      const capturedQuery = modelQuery('concurrent B citation').then(value => {
        querySettled = true;
        return value;
      }, error => { querySettled = true; throw error; });
      await new Promise(resolve => setTimeout(resolve, 300));
      requireThat(!statusSettled && !querySettled,
        'registry or serving-view capture escaped the shared publication lock');
      fs.writeFileSync(path.join(data, 'runtime', 'query-publication-release'), 'release');
      const [response, after, query] = await Promise.all([
        pendingCommit, capturedStatus, capturedQuery]);
      const result = read(response, 'query publication held commit');
      requireThat(response.status === 200 && result.state === 'COMPLETE'
        && after.readiness.engineComponents.encoders.state === 'READY'
        && after.readiness.engineComponents.encoders.appliedVersion !== initialVersion,
      `registry and B serving view did not publish together: ${JSON.stringify({
        response, encoders: after.readiness.engineComponents.encoders })}`);
      console.log('QUERY_ROLE_PUBLICATION_HOLD_PASS', JSON.stringify({
        beforeVersion: initialVersion,
        afterVersion: after.readiness.engineComponents.encoders.appliedVersion,
        scorer: query.scorer, witness: result.witness }));
      return;
    }
    const response = await request(apiPort, '/api/settings/v2', {
      method: 'POST', headers,
      body: JSON.stringify({ citationScorerModelPath: selected,
        witness: before.witness, operationKey }),
    }, 180000);
    const result = read(response, 'query-role commit');
    requireThat(response.status === 200 && result.state === 'COMPLETE',
      `query-role commit refused: HTTP ${response.status} ${response.text}`);
    const persisted = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    const citation = persisted.queryRoles?.citation;
    requireThat(persisted.acceptedRevision === before.witness.acceptedRevision + 1
      && citation?.state === 'SELECTED'
      && path.resolve(fileURLToPath(citation.model.path))
        === path.resolve(path.join(selected, 'model.onnx'))
      && citation.model.sha256 === hash(path.join(selected, 'model.onnx'))
      && citation.tokenizer.sha256 === hash(path.join(selected, 'tokenizer.json')),
    `query-role exact witness missing after commit: ${JSON.stringify(persisted)}`);
    const after = await waitFor('query-role B publication', 30000, async () => {
      const value = await status();
      const encoders = value.readiness.engineComponents.encoders;
      return encoders.appliedVersion && encoders.appliedVersion !== initialVersion ? value : null;
    });
    requireThat(after.readiness.engineComponents.encoders.state === 'READY',
      `query-role B did not become ready: ${JSON.stringify(after.readiness.engineComponents.encoders)}`);
    const query = await modelQuery('query-role B citation');
    if (scenario === 'query-role-issued-a') {
      requireThat(!heldSettled, 'issued A citation returned before B served');
      fs.writeFileSync(path.join(data, 'runtime', 'issued-a-citation-release'), 'release');
      const aResponse = await heldA;
      const a = read(aResponse, 'released A citation');
      requireThat(aResponse.status === 200 && a.ok === true
        && a.scorer === 'CROSS_ENCODER' && a.sentences_scored > 0,
      `issued A citation failed after B publication: ${aResponse.text}`);
      console.log('QUERY_ROLE_ISSUED_A_PASS', JSON.stringify({
        beforeVersion: initialVersion,
        afterVersion: after.readiness.engineComponents.encoders.appliedVersion,
        aScorer: a.scorer, bScorer: query.scorer, aSentences: a.sentences_scored,
        bSentences: query.sentences_scored }));
      return;
    }
    console.log('QUERY_ROLE_COMMIT_PASS', JSON.stringify({ operationKey,
      beforeVersion: initialVersion,
      afterVersion: after.readiness.engineComponents.encoders.appliedVersion,
      citation, scorer: query.scorer, sentencesScored: query.sentences_scored,
      worker: after.worker?.gpu }));
    return;
  }

  requireThat(scenario === 'query-role-boot' || scenario === 'query-role-tampered-boot',
    `unknown query-role scenario: ${scenario}`);
  const persisted = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
  const citation = persisted.queryRoles?.citation;
  requireThat(citation?.state === 'SELECTED' && citation.model.sha256 === hash(path.join(selected, 'model.onnx')),
  'query-role boot lost committed exact citation selection');
  requireThat(before.witness.acceptedRevision === persisted.acceptedRevision,
    'query-role boot changed the settings revision');
  if (scenario === 'query-role-tampered-boot') {
    requireThat(citation.tokenizer.sha256 !== hash(path.join(selected, 'tokenizer.json')),
      'tampered boot fixture did not change the selected supporting asset');
    const encoders = initial.readiness.engineComponents.encoders;
    requireThat(encoders.evidence?.includes('CITATION'),
      `tampered citation did not publish explicit unavailability: ${JSON.stringify(encoders)}`);
    const response = await post(apiPort, '/api/knowledge/match-citations', {
      answer_text: 'The quokka lives in Western Australia.',
      chunk_refs: [{ parent_doc_id: 'query-role-fixture', chunk_index: 0,
        passage_text: 'Quokkas live in Western Australia.' }],
    }, 30000);
    const fallback = read(response, 'tampered citation query');
    requireThat(response.status === 200 && fallback.ok === true
      && fallback.scorer !== 'CROSS_ENCODER',
    `tampered citation still executed cross-encoder: ${response.text}`);
    const lexical = await post(apiPort, '/api/knowledge/search',
      { query: 'quokka', limit: 5, mode: 'text' }, 15000);
    requireThat(lexical.status === 200,
      `tampered query role disabled keyword search: ${lexical.text}`);
    console.log('QUERY_ROLE_TAMPERED_BOOT_PASS', JSON.stringify({
      witness: before.witness, evidence: encoders.evidence,
      scorer: fallback.scorer, lexicalStatus: lexical.status }));
    return;
  }
  requireThat(initial.readiness.engineComponents.encoders.state === 'READY'
    && typeof initialVersion === 'string' && initialVersion.length > 0,
  `query-role boot did not publish the witnessed B: ${JSON.stringify(initial.readiness.engineComponents.encoders)}`);
  const query = await modelQuery('query-role boot citation');
  console.log('QUERY_ROLE_BOOT_PASS', JSON.stringify({ witness: before.witness,
    citation, appliedVersion: initialVersion, scorer: query.scorer,
    sentencesScored: query.sentences_scored, worker: initial.worker?.gpu }));
}

function operationRow(dbPath, operationKey) {
  const database = new DatabaseSync(dbPath, { readOnly: true });
  try {
    const rows = database.prepare(`SELECT id, operation_key, kind, state, phase,
      failure_reason, failure_detail, accepted_settings_revision
      FROM operations WHERE operation_key = ?`).all(operationKey);
    if (rows.length > 1) {
      throw new Error(`operation key is duplicated in SQLite: ${operationKey}`);
    }
    return rows[0] ?? null;
  } finally {
    database.close();
  }
}
