'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { libraryName } = require('../../lib/native-targets.cjs');

/** The shared runHeadlessEval/installed eval launch contract. Caller overrides retain precedence,
 * except the eval-only and IN_MEMORY settings that define this entry point. */
function evalLaunch(repoRoot, env = process.env) {
  const root = env.JUSTSEARCH_REPO_ROOT || repoRoot;
  const data = env.JUSTSEARCH_DATA_DIR || path.join(repoRoot, 'tmp/headless-eval-data');
  const environment = Object.fromEntries(Object.entries(env).filter(([key, value]) => key.startsWith('JUSTSEARCH_') && value));
  const defaults = {
    JUSTSEARCH_API_PORT: '33221', JUSTSEARCH_DATA_DIR: data, JUSTSEARCH_HOME: data,
    JUSTSEARCH_INDEX_BASE_PATH: path.join(data, 'index/default'), JUSTSEARCH_REPO_ROOT: root,
    JUSTSEARCH_SSOT_PATH: path.join(root, 'SSOT'),
    JUSTSEARCH_STAGE_PLUGIN_MANIFEST: path.join(root, 'SSOT/manifests/plugins/pipeline-stage-plugins.v1.json'),
    JUSTSEARCH_CONFIG: path.join(repoRoot, 'modules/ui/src/main/resources/headless-config/application.yaml'),
    JUSTSEARCH_MODELS_DIR: path.join(root, 'models'),
  };
  for (const [key, value] of Object.entries(defaults)) if (!environment[key]) environment[key] = value;
  environment.JUSTSEARCH_UI_SETTINGS_MODE = 'IN_MEMORY';
  environment.JUSTSEARCH_HOME = data;
  if (process.platform === 'win32' && !environment.JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH) {
    const roots = [repoRoot];
    try {
      const git = fs.readFileSync(path.join(repoRoot, '.git'), 'utf8').trim().match(/^gitdir:\s*(.+)$/);
      if (git) roots.push(path.resolve(repoRoot, git[1], '../../..'));
    } catch {}
    const ort = roots.map(r => path.join(r, 'tmp/ort-variant-test/cuda-12.4-v1.24.3'))
      .find(p => fs.existsSync(path.join(p, libraryName('ort-cuda'))));
    if (ort) environment.JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH = ort;
  }
  if (environment.JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH && !environment.JUSTSEARCH_GPU_ENABLED) environment.JUSTSEARCH_GPU_ENABLED = 'true';
  const properties = {
    'justsearch.api.port': environment.JUSTSEARCH_API_PORT,
    'justsearch.data.dir': data, 'justsearch.home': data, 'justsearch.ui.settings.mode': 'IN_MEMORY',
    'justsearch.index.base_path': environment.JUSTSEARCH_INDEX_BASE_PATH,
    'justsearch.config': environment.JUSTSEARCH_CONFIG, 'justsearch.repo.root': root,
    'justsearch.ssot.path': environment.JUSTSEARCH_SSOT_PATH,
    'justsearch.plugins.manifest': environment.JUSTSEARCH_STAGE_PLUGIN_MANIFEST,
    'justsearch.models.dir': environment.JUSTSEARCH_MODELS_DIR, 'justsearch.eval.mode': 'true',
  };
  for (const [key, prop] of Object.entries({ JUSTSEARCH_SERVER_EXE: 'justsearch.server.exe',
    JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH: 'justsearch.onnxruntime.native_path',
    JUSTSEARCH_LLM_ENABLED: 'justsearch.llm.enabled', JUSTSEARCH_LLM_MODEL_PATH: 'justsearch.llm.model_path' })) {
    if (environment[key]) properties[prop] = environment[key];
  }
  const jvmArgs = Object.entries(properties).map(([key, value]) => `-D${key}=${value}`);
  if (environment.JUSTSEARCH_JFR_ENABLED === 'true') jvmArgs.push('-XX:+FlightRecorder',
    `-XX:StartFlightRecording=filename=${path.join(repoRoot, 'tmp/eval-profile.jfr')},duration=300s,settings=profile`);
  if (environment.JUSTSEARCH_ORT_PROFILING === 'true') environment.JUSTSEARCH_ORT_PROFILING_DIR = path.join(repoRoot, 'tmp');
  return { environment, jvmArgs };
}
module.exports = { evalLaunch };
if (require.main === module) console.log(JSON.stringify(evalLaunch(path.resolve(__dirname, '../../..'))));
