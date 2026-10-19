/**
 * Scenario repos for the test-intent detection corpus (tempdoc 966 D1 "Detection corpus") and the
 * kernel self-test fixtures.
 *
 * A scenario is plain data (JSON-able), so `_fixtures/test-intent/{positive,negative}/scenario.json`
 * and the corpus in corpus.mjs share one format. `runScenario` builds a real scratch git repo — a
 * `main` branch, a PR branch with commits, optional acceptance records computed by the gate's own
 * digest, optional moves of `main` — then realises the CI event (pull_request merge ref, merge-group
 * commit, squash push to main, or a local working tree) and runs the real analysis on it. Nothing
 * is mocked: the diff, the base resolution and the changeset loading are the production code paths.
 *
 * Scenario fields:
 *   main:        { path: content }                         initial commit on main
 *   branch:      [ step ]                                  commits on the PR branch
 *   changeset:   { name, frontmatter?, entries, prose? }   committed as the last branch commit
 *   accept:      [ { kind?, role, session, verdict, digestOverride? } ]   records, then committed
 *   afterAccept: [ step ]                                  commits after acceptance
 *   acceptAgain: [ record ]                                records on the digest after afterAccept
 *   mainMoves:   [ step ]                                  commits on main after the branch
 *   uncommitted: step                                      working-tree edits (local event only)
 *   event:       { name: local|pull_request|merge_group|push, pr?, fork? }
 *   shallow:     true                                      run in a depth-1 clone
 *   step:        { write?: {path: content}, delete?: [path], move?: [[from, to]],
 *                  replaceIn?: {path: [from, to]}, message? }
 */

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

import { analyzeTestIntent } from './analyze.mjs';
import { ACCEPTANCE_HEADING, CHANGESETS_DIR } from './classifications.mjs';
import { appendRecord } from './changeset.mjs';

const GIT_ENV = {
  GIT_AUTHOR_NAME: 'corpus',
  GIT_AUTHOR_EMAIL: 'corpus@example.invalid',
  GIT_COMMITTER_NAME: 'corpus',
  GIT_COMMITTER_EMAIL: 'corpus@example.invalid',
};

function gitIn(cwd, ...args) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    env: { ...process.env, ...GIT_ENV },
    stdio: ['pipe', 'pipe', 'pipe'],
  }).trim();
}

function applyStep(root, step) {
  for (const [from, to] of step.move ?? []) {
    fs.mkdirSync(path.dirname(path.join(root, to)), { recursive: true });
    gitIn(root, 'mv', from, to);
  }
  for (const rel of step.delete ?? []) gitIn(root, 'rm', '-q', rel);
  for (const [rel, content] of Object.entries(step.write ?? {})) {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  }
  for (const [rel, [from, to]] of Object.entries(step.replaceIn ?? {})) {
    const abs = path.join(root, rel);
    const text = fs.readFileSync(abs, 'utf8');
    if (!text.includes(from)) throw new Error(`scenario: '${from}' not found in ${rel}`);
    fs.writeFileSync(abs, text.replace(from, to), 'utf8');
  }
}

function commitStep(root, step, fallback) {
  applyStep(root, step);
  gitIn(root, 'add', '-A');
  gitIn(root, 'commit', '-q', '--allow-empty', '-m', step.message ?? fallback);
}

/** Text of a changeset in the documented format. */
export function changesetText({ frontmatter = {}, entries, prose = 'Corpus changeset.' }) {
  const fm = { schema: 'test-intent.v1', task: 't-corpus-1', 'author-role': 'builder', 'author-session': 'builder-session-1', ...frontmatter };
  return [
    '---',
    ...Object.entries(fm).filter(([, v]) => v !== null).map(([k, v]) => `${k}: ${v}`),
    '---',
    '',
    prose,
    '',
    '```json',
    JSON.stringify({ entries }, null, 2),
    '```',
    '',
    ACCEPTANCE_HEADING,
    '',
  ].join('\n');
}

/**
 * Build the scenario repo and run the analysis.
 * @returns {{result: object, root: string, cleanup: () => void}}
 */
export function runScenario(scenario) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'test-intent-'));
  const root = path.join(tmp, 'repo');
  fs.mkdirSync(root);
  gitIn(root, 'init', '-q', '-b', 'main');
  gitIn(root, 'config', 'core.autocrlf', 'false');
  gitIn(root, 'config', 'commit.gpgsign', 'false');
  commitStep(root, { write: { 'README.md': 'scratch\n', ...(scenario.main ?? {}) } }, 'main: initial');
  gitIn(root, 'checkout', '-q', '-b', 'pr');
  (scenario.branch ?? []).forEach((s, i) => commitStep(root, s, `pr: step ${i + 1}`));

  const csRel = scenario.changeset ? `${CHANGESETS_DIR}/${scenario.changeset.name ?? 'corpus'}.md` : null;
  if (scenario.changeset) {
    commitStep(root, { write: { [csRel]: changesetText(scenario.changeset) } }, 'pr: changeset');
  }
  // The acceptor computes the digest with the gate itself, exactly as `cli.mjs accept` does.
  const acceptRound = (records, message) => {
    if (!records?.length) return;
    const pre = analyzeTestIntent({ repoRoot: root, env: {} });
    const cs = pre.changesets.find((c) => c.path === csRel);
    if (!cs?.digest) throw new Error(`scenario: no digest for ${csRel} (changeset invalid?)`);
    let text = fs.readFileSync(path.join(root, csRel), 'utf8');
    for (const r of records) {
      const { digestOverride, ...rec } = r;
      text = appendRecord(text, { kind: 'acceptance', ...rec, digest: digestOverride ?? cs.digest });
    }
    commitStep(root, { write: { [csRel]: text } }, message);
  };
  acceptRound(scenario.accept, 'pr: acceptance record');
  (scenario.afterAccept ?? []).forEach((s, i) => commitStep(root, s, `pr: after acceptance ${i + 1}`));
  acceptRound(scenario.acceptAgain, 'pr: acceptance record on the new content');

  gitIn(root, 'checkout', '-q', 'main');
  (scenario.mainMoves ?? []).forEach((s, i) => commitStep(root, s, `main: moves ${i + 1}`));

  const ev = scenario.event ?? { name: 'local' };
  const pr = ev.pr ?? 7;
  const env = {};
  const payloadPath = path.join(tmp, 'event.json');
  if (ev.name === 'local') {
    gitIn(root, 'checkout', '-q', 'pr');
    if (scenario.uncommitted) applyStep(root, scenario.uncommitted);
  } else if (ev.name === 'pull_request') {
    gitIn(root, 'checkout', '-q', '--detach', 'main');
    gitIn(root, 'merge', '-q', '--no-ff', '-m', `Merge pr into main`, 'pr');
    fs.writeFileSync(payloadPath, JSON.stringify({
      pull_request: {
        number: pr,
        base: { ref: 'main', repo: { full_name: 'org/repo' } },
        head: { repo: { full_name: ev.fork ? 'someone/repo' : 'org/repo', fork: Boolean(ev.fork) } },
      },
    }));
    Object.assign(env, { GITHUB_EVENT_NAME: 'pull_request', GITHUB_BASE_REF: 'main', GITHUB_EVENT_PATH: payloadPath });
  } else if (ev.name === 'merge_group') {
    const baseSha = gitIn(root, 'rev-parse', 'main');
    gitIn(root, 'checkout', '-q', '--detach', 'main');
    gitIn(root, 'merge', '-q', '--squash', 'pr');
    gitIn(root, 'commit', '-q', '-m', `Change (#${pr})`);
    fs.writeFileSync(payloadPath, JSON.stringify({
      merge_group: { base_sha: baseSha, head_ref: `refs/heads/gh-readonly-queue/main/pr-${pr}-${baseSha}` },
    }));
    Object.assign(env, { GITHUB_EVENT_NAME: 'merge_group', GITHUB_EVENT_PATH: payloadPath });
  } else if (ev.name === 'push') {
    gitIn(root, 'merge', '-q', '--squash', 'pr');
    gitIn(root, 'commit', '-q', '-m', `Change (#${pr})`);
    Object.assign(env, { GITHUB_EVENT_NAME: 'push' });
  } else {
    throw new Error(`scenario: unknown event '${ev.name}'`);
  }

  let runRoot = root;
  if (scenario.shallow) {
    runRoot = path.join(tmp, 'shallow');
    execFileSync('git', ['clone', '-q', '--depth', '1', '--no-local', pathToFileURL(root).href, runRoot], { stdio: 'pipe' });
  }
  const cleanup = () => fs.rmSync(tmp, { recursive: true, force: true });
  try {
    const result = analyzeTestIntent({ repoRoot: runRoot, env });
    return { result, root: runRoot, cleanup };
  } catch (e) {
    cleanup();
    throw e;
  }
}
