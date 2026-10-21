import assert from 'node:assert/strict';
import test from 'node:test';
import { PR_FLAKES, skipPrFlake } from '../ci/pr-flakes.mjs';
import { casesForAdapter, engineRow } from './contract.mjs';
import { phases as devPhases } from './adapters/dev-runner.mjs';
import { phases as tauriPhases } from './adapters/tauri.mjs';

for (const [adapter, phases] of [['dev-runner', devPhases], ['tauri', tauriPhases]]) {
  test(`${adapter} excludes exactly six listed conformance cases on PRs and runs all on main`, () => {
    const original = process.env.JUSTSEARCH_PR_CI;
    const log = console.log;
    const messages = [];
    console.log = message => messages.push(message);
    try {
      const cases = casesForAdapter(phases, engineRow(), adapter);
      const excluded = PR_FLAKES.filter(row => row.runner === 'supervisor').map(row => row.identifier).sort();
      assert.equal(excluded.length, 6);
      process.env.JUSTSEARCH_PR_CI = 'true';
      const selected = cases.filter(row => !skipPrFlake('scripts/supervisor-conformance/run.mjs', row.id));
      assert.deepEqual(cases.filter(row => !selected.includes(row)).map(row => row.id).sort(), excluded);
      assert.equal(messages.length, 6, 'each exclusion is logged once');
      assert.ok(selected.length > 30, 'the rest of the conformance suite still runs');
      assert.equal(skipPrFlake('scripts/supervisor-conformance/run.mjs', 'new-deterministic-case'), false);
      messages.length = 0;
      process.env.JUSTSEARCH_PR_CI = 'false';
      assert.deepEqual(cases.filter(row => !skipPrFlake('scripts/supervisor-conformance/run.mjs', row.id)), cases);
      assert.deepEqual(messages, []);
    } finally {
      console.log = log;
      if (original === undefined) delete process.env.JUSTSEARCH_PR_CI;
      else process.env.JUSTSEARCH_PR_CI = original;
    }
  });
}
