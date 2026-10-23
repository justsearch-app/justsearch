#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { PR_FLAKES } from './pr-flakes.mjs';
const files = process.argv.slice(2);
const skipped = process.env.JUSTSEARCH_PR_CI === 'true'
  ? PR_FLAKES.filter((row) => row.runner === 'node' && files.includes(row.source)) : [];
for (const row of skipped) console.log(`SKIP PR flake: ${row.source}: ${row.identifier}`);
const escape = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const args = ['--test', ...(skipped.length ? ['--test-skip-pattern', skipped.map((row) => escape(row.identifier)).join('|')] : []), ...files];
const result = spawnSync(process.execPath, args, { stdio: 'inherit' });
if (result.error) console.error(result.error.message);
process.exitCode = result.status ?? 1;
