/** Hosted report reuse, including advisory failure and early base-resolution fallback. */
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { appendRecord } from './changeset.mjs';
import { enforceTestIntent } from './enforcer.mjs';
import { changesetText } from './scenario.mjs';
import { ROLLING_DAYS, renderMarkdown, reportFor } from './weekly-report.mjs';

const NOW = '2026-11-03T12:00:00.000Z';

for (const mode of ['report', 'report-error', 'base-error']) {
  test(`hosted summary: ${mode} traverses rolling history only once`, async (t) => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'test-intent-hosted-'));
    const repoRoot = path.join(tmp, 'repo');
    fs.mkdirSync(repoRoot);
    const summary = path.join(tmp, 'summary.md');
    const savedEnv = process.env;
    const execFileSync = childProcess.execFileSync;
    const RealDate = Date;
    try {
      process.env = {
        ...savedEnv,
        GIT_AUTHOR_NAME: 'summary-test', GIT_AUTHOR_EMAIL: 'summary@example.invalid',
        GIT_COMMITTER_NAME: 'summary-test', GIT_COMMITTER_EMAIL: 'summary@example.invalid',
        GIT_AUTHOR_DATE: NOW, GIT_COMMITTER_DATE: NOW,
        GITHUB_EVENT_NAME: 'pull_request', GITHUB_BASE_REF: mode === 'base-error' ? 'missing' : 'main',
        GITHUB_EVENT_PATH: '', GITHUB_STEP_SUMMARY: summary,
      };
      const git = (...args) => execFileSync('git', args, { cwd: repoRoot, stdio: 'pipe' });
      git('init', '-q', '-b', 'main');
      git('config', 'commit.gpgsign', 'false');
      const dir = path.join(repoRoot, 'gates/test-intent/.changesets');
      fs.mkdirSync(dir, { recursive: true });
      for (let i = 0; i < 10; i++) {
        let text = changesetText({ entries: [{ items: ['a'], class: 'New', newChecks: ['a#x'] }] });
        const records = [
          { kind: 'acceptance', session: 'v', verdict: 'accept' },
          { kind: 'audit', session: 'a', verdict: i < 2 ? 'reject' : 'accept' },
        ];
        if (i === 0) records.push({ kind: 'tie-break', session: 't', verdict: 'accept' });
        if (i === 1) records.push(
          { kind: 'acceptance', session: 'v', verdict: 'accept', digest: 'repaired' },
          { kind: 'audit', session: 'a', verdict: 'accept', digest: 'repaired' },
        );
        for (const record of records) text = appendRecord(text, { role: 'verifier', digest: 'd', ...record });
        fs.writeFileSync(path.join(dir, `${i}.md`), text);
        git('add', '-A');
        git('commit', '-q', '-m', `Add a check (#${15 + i * 5})`);
      }
      const report = reportFor({ repoRoot, ref: 'main', days: ROLLING_DAYS, now: new Date(NOW) });
      assert.equal(report.totals.audited, 10);
      assert.equal(report.totals.auditDisagreements, 2);
      assert.equal(report.totals.tieBreaks, 1);
      assert.equal(report.findings.length, 1);

      // Count the real history subprocesses; only the error case replaces their result.
      let historyCalls = 0;
      t.mock.method(childProcess, 'execFileSync', (file, args, options) => {
        if (file === 'git' && args[0] === 'log' && args.includes('--first-parent')) {
          historyCalls += 1;
          if (mode === 'report-error') throw new Error('history unavailable\ndetails');
        }
        return execFileSync(file, args, options);
      });
      syncBuiltinESMExports();
      t.mock.method(globalThis, 'Date', class extends RealDate {
        constructor(...args) { super(...(args.length ? args : [NOW])); }
      });
      const output = [];
      t.mock.method(console, 'log', (line) => output.push(line));

      const result = await enforceTestIntent({ repoRoot });
      const md = fs.readFileSync(summary, 'utf8');
      const warning = result.findings.find((f) => f.ruleId === 'test-intent/reconsider-rate');
      if (mode === 'report-error') {
        assert.equal(md, '# Test-intent counts\n\nNot available on this run: history unavailable\n\n');
        assert.equal(result.verdict, 'pass', 'report errors remain advisory');
        assert.equal(warning.level, 'note');
        assert.equal(warning.message, 'rolling test-intent counts unavailable here: history unavailable');
      } else {
        assert.equal(md, renderMarkdown(report) + '\n', 'summary bytes and counts are preserved');
        if (mode === 'base-error') {
          assert.equal(result.verdict, 'fail');
          assert.ok(result.findings.some((f) => f.ruleId === 'test-intent/base-unresolved'));
        } else {
          assert.equal(result.verdict, 'pass', 'reconsider warning remains advisory');
          assert.equal(warning.level, 'warning');
          assert.equal(warning.message, report.findings[0]);
          assert.match(warning.message, /PRs with a reviewer disagreement or third-reviewer tie-break were 2 of 10 audited PRs \(20\.0%\)/);
          assert.match(output.join('\n'), /WARNING test-intent\/reconsider-rate \(does not fail the gate\)/);
        }
      }
      assert.equal(historyCalls, mode === 'report-error' ? 1 : 2,
        'one traversal: two git log calls on success, one on error');
    } finally {
      t.mock.restoreAll();
      syncBuiltinESMExports();
      process.env = savedEnv;
      fs.rmSync(tmp, { recursive: true, force: true });
    }
  });
}
