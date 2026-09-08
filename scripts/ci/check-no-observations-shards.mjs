#!/usr/bin/env node
/**
 * check-no-observations-shards — the observations inbox is retired (tempdoc 872).
 *
 * `docs/observations.d/` was the per-session shard directory that `note-observation.mjs`
 * wrote and `fold-observations.mjs` folded into `docs/observations.md`. Both are gone; a
 * shard that appears now comes from a session still carrying the pre-872 brief, and with no
 * fold it would sit unread forever — the exact pile 872 removed. This check turns that into
 * a legible failure at the PR instead: route the note (canonical agent-workflow.md `rule:log-pre-existing-issues`)
 * and delete the file.
 *
 *   node scripts/ci/check-no-observations-shards.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const DIR = path.join(ROOT, 'docs', 'observations.d');

if (!fs.existsSync(DIR)) {
  console.log('check-no-observations-shards: OK — docs/observations.d/ does not exist');
  process.exit(0);
}
const files = fs.readdirSync(DIR);
console.log(`check-no-observations-shards: FAIL — docs/observations.d/ exists (${files.length} file(s)); the inbox was retired in tempdoc 872.`);
for (const f of files) console.log(`  ${f}`);
console.log('Route each `- [ ]` note per docs/reference/contributing/agent-workflow.md `rule:log-pre-existing-issues` (fix in place / rules / owning tempdoc), then remove only shards owned by this task.');
process.exit(1);
