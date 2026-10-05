#!/usr/bin/env node
/**
 * test-intent gate, local entry point (tempdoc 966 D1). Same analysis as the kernel run in CI.
 *
 *   node scripts/governance/gates/test-intent/cli.mjs [--base <ref>] [--pr <n>] [--json]
 *        check the working tree against the base (default: merge-base with origin/main or main)
 *   node scripts/governance/gates/test-intent/cli.mjs --skeleton <name> [--task <id>] [--session <id>]
 *        also write gates/test-intent/.changesets/<name>.md listing every uncovered flagged item
 *   node scripts/governance/gates/test-intent/cli.mjs digest <changeset>
 *        print the changeset's current acceptance digest
 *   node scripts/governance/gates/test-intent/cli.mjs accept <changeset> --role verifier|orchestrator|reviewer
 *        --session <id> --verdict accept|reject [--kind acceptance|audit|tie-break] [--note <text>]
 *        append an acceptance record bound to the current digest (the ACCEPTOR runs this, never the author)
 *
 * Exit: 0 pass, 1 fail, 2 usage or runner error. Rules: docs/reference/testing/test-intent.md.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { analyzeTestIntent, formatReport } from './analyze.mjs';
import { RECORD_KINDS, VERDICTS } from './classifications.mjs';
import { appendRecord, writeSkeleton } from './changeset.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');

function parse(argv) {
  const args = { cmd: 'check', positional: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const val = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${a} needs a value`);
      return v;
    };
    if (a === '--base') args.base = val();
    else if (a === '--pr') args.pr = Number.parseInt(val(), 10);
    else if (a === '--json') args.json = true;
    else if (a === '--skeleton') args.skeleton = val();
    else if (a === '--task') args.task = val();
    else if (a === '--session') args.session = val();
    else if (a === '--role') args.role = val();
    else if (a === '--verdict') args.verdict = val();
    else if (a === '--kind') args.kind = val();
    else if (a === '--note') args.note = val();
    else if (a === '--repo') args.repo = val();
    else if (a === '-h' || a === '--help') args.cmd = 'help';
    else if (a.startsWith('--')) throw new Error(`unknown option ${a}`);
    else if (args.positional.length === 0 && ['digest', 'accept', 'check'].includes(a) && args.cmd === 'check') args.cmd = a;
    else args.positional.push(a);
  }
  return args;
}

function main() {
  let args;
  try {
    args = parse(process.argv.slice(2));
  } catch (e) {
    console.error(e.message);
    return 2;
  }
  if (args.cmd === 'help') {
    console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/')[0]);
    return 0;
  }
  const repoRoot = args.repo ? resolve(args.repo) : REPO_ROOT;
  const result = analyzeTestIntent({ repoRoot, env: process.env, explicitBase: args.base ?? null, prOverride: args.pr ?? null });

  if (args.cmd === 'digest' || args.cmd === 'accept') {
    const rel = (args.positional[0] ?? '').replaceAll('\\', '/');
    const cs = result.changesets.find((c) => c.path === rel);
    if (!cs) {
      console.error(`${rel || '<changeset>'} is not a parseable test-intent changeset in this branch`);
      return 2;
    }
    if (!cs.digest) {
      console.error(`${rel} has no entries that need acceptance`);
      return 2;
    }
    if (args.cmd === 'digest') {
      console.log(cs.digest);
      return 0;
    }
    const kind = args.kind ?? 'acceptance';
    if (!RECORD_KINDS.includes(kind)) return usage(`--kind must be one of ${RECORD_KINDS.join(', ')}`);
    if (!VERDICTS.includes(args.verdict)) return usage(`--verdict must be one of ${VERDICTS.join(', ')}`);
    if (!args.role || !args.session) return usage('--role and --session are required');
    if (args.session === cs.frontmatter['author-session']) {
      console.error('refused: this session wrote the entries; the acceptor must be someone other than the author');
      return 1;
    }
    const abs = resolve(repoRoot, rel);
    const record = { kind, role: args.role, session: args.session, verdict: args.verdict, digest: cs.digest };
    if (args.note) record.note = args.note;
    writeFileSync(abs, appendRecord(readFileSync(abs, 'utf8'), record), 'utf8');
    console.log(`appended ${kind} record (${args.verdict}) to ${rel} bound to ${cs.digest}`);
    return 0;
  }

  if (args.skeleton) {
    if (result.uncovered.length === 0) {
      console.log('no uncovered flagged items; no skeleton written');
    } else {
      const rel = writeSkeleton({ repoRoot, name: args.skeleton, items: result.uncovered, task: args.task, session: args.session });
      console.log(`skeleton changeset written: ${rel} (${result.uncovered.length} item(s))`);
    }
  }
  if (args.json) console.log(JSON.stringify(result, null, 2));
  else console.log(formatReport(result));
  return result.verdict === 'pass' ? 0 : 1;
}

function usage(msg) {
  console.error(msg);
  return 2;
}

try {
  process.exitCode = main();
} catch (e) {
  console.error(`test-intent: runner error: ${e.message}`);
  process.exitCode = 2;
}
