import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

/** Installed query-only settings and subsequent boot use the same exact settings witness. */
export async function exerciseQueryRoleScenario(c) {
  const { scenario, work, data, modelsRoot, apiPort, manifest, request, post, waitFor,
    requireThat, createOperationKey } = c;
  const source = path.join(modelsRoot, 'onnx', 'citation-scorer');
  const selected = path.join(work, 'query-citation-b');
  const settingsPath = path.join(data, 'ui', 'settings.json');
  const hash = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const read = (response, label) => {
    try { return JSON.parse(response.text); }
    catch { throw new Error(`${label} returned invalid JSON: ${response.text}`); }
  };
  const status = async () => {
    const response = await request(apiPort, '/api/status', {}, 15000);
    requireThat(response.status === 200, `query-role status failed: ${response.text}`);
    return read(response, 'query-role status');
  };
  const modelQuery = async (label) => {
    const response = await post(apiPort, '/api/knowledge/match-citations', {
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

  if (scenario === 'query-role-commit') {
    fs.mkdirSync(selected, { recursive: true });
    for (const name of ['model.onnx', 'tokenizer.json']) {
      const target = path.join(selected, name);
      if (!fs.existsSync(target)) {
        if (name === 'tokenizer.json') fs.copyFileSync(path.join(source, name), target);
        else fs.linkSync(path.join(source, name), target);
      }
    }
    const manifestFile = path.join(source, 'model_manifest.json');
    if (fs.existsSync(manifestFile)) {
      fs.copyFileSync(manifestFile, path.join(selected, 'model_manifest.json'));
    }
    const operationKey = createOperationKey();
    const headers = { 'content-type': 'application/json' };
    const token = manifest.head?.sessionToken;
    if (typeof token === 'string' && token.length > 0) {
      headers['X-JustSearch-Session'] = token;
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
