/** Installed standard-model proof for accepted inference refresh through core.reconfigure. */
export async function exerciseReconfigureRefresh(c) {
  const { apiPort, manifest, request, post, waitFor, requireThat, createOperationKey } = c;
  const token = manifest.head?.sessionToken;
  const headers = { 'content-type': 'application/json' };
  if (typeof token === 'string' && token.length > 0) headers['X-JustSearch-Session'] = token;
  const json = (response, label) => {
    try { return JSON.parse(response.text); }
    catch { throw new Error(`${label} returned invalid JSON: ${response.text}`); }
  };
  const activation = await request(apiPort, '/api/ai/runtime/activate', {
    method: 'POST', headers, body: JSON.stringify({ variantId: 'cuda12', chatProfile: 'standard' }),
  }, 30000);
  requireThat(activation.status === 200,
    `standard-model activation refused: HTTP ${activation.status} ${activation.text}`);
  await waitFor('standard model serving through Head proxy', 240000, async () => {
    const activationStatus = await request(apiPort, '/api/ai/runtime/status', {}, 5000);
    if (activationStatus.status === 200) {
      const activationState = json(activationStatus, 'runtime activation status').activation;
      if (activationState?.state === 'failed') {
        throw new Error(`standard-model activation failed: ${JSON.stringify(activationState)}`);
      }
    }
    try {
      const models = await request(apiPort, '/v1/models', {}, 5000);
      return models.status === 200 ? models : null;
    } catch { return null; }
  });
  await waitFor('standard model logical Online publication', 30000, async () => {
    const response = await request(apiPort, '/api/inference/status');
    if (response.status !== 200) return null;
    const status = json(response, 'inference activation status');
    return status.mode === 'online' && Number.isSafeInteger(status.generation)
      && status.generation > 0 ? status : null;
  });
  const query = async (label) => {
    const response = await post(apiPort, '/v1/chat/completions', {
      model: 'local', messages: [{ role: 'user', content: 'Reply with one short word.' }],
      max_tokens: 24, stream: false,
    }, 120000);
    const body = json(response, label);
    requireThat(response.status === 200 && Array.isArray(body.choices) && body.choices.length > 0,
      `${label} failed: HTTP ${response.status} ${response.text}`);
    return body;
  };
  await query('query before refresh');
  const statusBeforeResponse = await request(apiPort, '/api/inference/status');
  requireThat(statusBeforeResponse.status === 200, `inference status failed: ${statusBeforeResponse.text}`);
  const statusBefore = json(statusBeforeResponse, 'inference status before refresh');
  requireThat(Number.isSafeInteger(statusBefore.generation) && statusBefore.generation > 0,
    `standard model had no serving generation: ${statusBeforeResponse.text}`);
  const modelsBeforeResponse = await request(apiPort, '/v1/models');
  const modelsBefore = json(modelsBeforeResponse, 'models before refresh');
  const modelBefore = modelsBefore.data?.[0]?.id;
  requireThat(modelsBeforeResponse.status === 200 && typeof modelBefore === 'string'
    && statusBefore.activeModelId === modelBefore,
  `inference status did not name the physical model before refresh: ${statusBeforeResponse.text} / ${modelsBeforeResponse.text}`);
  const settingsResponse = await request(apiPort, '/api/settings/v2');
  requireThat(settingsResponse.status === 200, `settings read failed: ${settingsResponse.text}`);
  const settings = json(settingsResponse, 'settings before refresh');
  const operationKey = createOperationKey();
  const candidate = { witness: settings.witness, operationKey };
  const refreshed = await request(apiPort, '/api/settings/v2', {
    method: 'POST',
    headers: { ...headers, 'X-JustSearch-Refresh-Inference': 'true' },
    body: JSON.stringify(candidate),
  }, 240000);
  const result = json(refreshed, 'accepted inference refresh');
  requireThat(refreshed.status === 200 && result.state === 'COMPLETE'
    && result.witness?.lastCommittedOperationKey === operationKey,
  `accepted inference refresh failed: HTTP ${refreshed.status} ${refreshed.text}`);
  const statusAfterResponse = await request(apiPort, '/api/inference/status');
  const statusAfter = json(statusAfterResponse, 'inference status after refresh');
  requireThat(statusAfterResponse.status === 200 && statusAfter.generation > statusBefore.generation,
    `refresh did not publish a new serving generation: ${statusAfterResponse.text}`);
  const modelsAfterResponse = await request(apiPort, '/v1/models');
  const modelsAfter = json(modelsAfterResponse, 'models after refresh');
  requireThat(modelsAfterResponse.status === 200
    && modelsAfter.data?.[0]?.id === modelBefore && statusAfter.activeModelId === modelBefore,
  `refresh changed or mislabeled the physical model: ${statusAfterResponse.text} / ${modelsAfterResponse.text}`);
  await query('query after refresh');
  const settingsAfterResponse = await request(apiPort, '/api/settings/v2');
  const settingsAfter = json(settingsAfterResponse, 'settings after refresh');
  requireThat(settingsAfter.witness?.acceptedRevision === settings.witness.acceptedRevision + 1
    && settingsAfter.witness?.lastCommittedOperationKey === operationKey,
  `refresh did not commit the exact successor witness: ${settingsAfterResponse.text}`);
  console.log('PASS reconfigure-refresh', JSON.stringify({ operationKey,
    beforeGeneration: statusBefore.generation, afterGeneration: statusAfter.generation,
    physicalModel: modelBefore,
    beforeWitness: settings.witness, afterWitness: settingsAfter.witness }));
}
