/**
 * --preflight [<baselineRef>] — Layer 3 §3.5 of tempdoc 530.
 *
 * Predicts which gates *could* fail given the diff between baselineRef and
 * HEAD. Uses each gate's input-glob set from the registry; doesn't actually
 * run the gates. Lets the agent target fixes per-gate rather than running
 * every gate to see which one fails.
 *
 * For ratchet-file gates, the baseline file itself is always relevant — any
 * diff against the baseline ref might shift the baseline.
 */

import { execFileSync } from 'node:child_process';

import { isShallowRepository, resolveBaselineRef } from './git-utils.mjs';

function diffPaths(baselineRef, repoRoot) {
  try {
    const out = execFileSync(
      'git',
      ['diff', '--name-only', `${baselineRef}...HEAD`],
      { cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] },
    );
    return out.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
  } catch {
    return [];
  }
}

/** Convert a simple glob to a regex (same shape as the class-size enforcer). */
function globToRegex(glob) {
  let re = '';
  let i = 0;
  while (i < glob.length) {
    const ch = glob[i];
    if (ch === '*') {
      if (glob[i + 1] === '*') { re += '.*'; i += 2; if (glob[i] === '/') i++; continue; }
      re += '[^/]*'; i++; continue;
    }
    if (ch === '?') { re += '[^/]'; i++; continue; }
    if (ch === '{') {
      const end = glob.indexOf('}', i);
      if (end !== -1) { re += `(?:${glob.slice(i + 1, end).split(',').join('|')})`; i = end + 1; continue; }
    }
    if ('.+^$()|[]\\'.includes(ch)) { re += '\\' + ch; i++; continue; }
    re += ch; i++;
  }
  return new RegExp('^' + re + '$');
}

function gateMatchesPaths(gate, changedPaths) {
  const reasons = [];

  // Direct baseline-file edits always count.
  if (gate.baseline?.path && changedPaths.includes(gate.baseline.path)) {
    reasons.push(`baseline edited: ${gate.baseline.path}`);
  }

  // Changesets dir edits are author-side.
  if (gate.changesetsDir && changedPaths.some(p => p.startsWith(gate.changesetsDir + '/'))) {
    reasons.push(`changeset(s) authored under ${gate.changesetsDir}`);
  }

  // Source-glob hits.
  const include = (gate.config?.sourceGlobs ?? []).map(globToRegex);
  const exclude = (gate.config?.excludeGlobs ?? []).map(globToRegex);
  if (include.length > 0) {
    const hits = changedPaths.filter(p => include.some(re => re.test(p)) && !exclude.some(re => re.test(p)));
    if (hits.length > 0) reasons.push(`${hits.length} source file(s) match include globs`);
  }

  // Specific config-paths (a gate's produced report, or a dist dir it measures).
  for (const k of ['reportPath', 'distDir']) {
    const cfgPath = gate.config?.[k];
    if (cfgPath && changedPaths.some(p => p === cfgPath || p.startsWith(cfgPath + '/'))) {
      reasons.push(`${k} touched: ${cfgPath}`);
    }
  }

  return reasons;
}

export async function runPreflight({ baselineRef, gates, repoRoot }) {
  if (isShallowRepository(repoRoot)) {
    console.error('shallow clone detected; --preflight needs full history.');
    process.exit(2);
  }
  let ref = baselineRef;
  try {
    // `--preflight <ref>` names the ref deliberately, so it is `explicit`, not a
    // fallback: the git-base ladder must not silently prefer a merge-base over
    // what the operator asked for. `diffPaths` still uses `<ref>...HEAD`, so an
    // explicit branch name is compared from its merge-base anyway.
    ref = resolveBaselineRef({ strategy: 'git-base', explicit: baselineRef }, repoRoot).ref;
  } catch (err) {
    console.error(`baseline ref '${baselineRef}' unreachable: ${err.message}`);
    process.exit(2);
  }

  const changedPaths = diffPaths(ref, repoRoot);
  if (changedPaths.length === 0) {
    console.log(`No path changes vs ${ref}; no gates affected.`);
    return;
  }

  console.log(`Preflight against ${ref} (${changedPaths.length} changed path${changedPaths.length === 1 ? '' : 's'}):`);
  console.log('');

  const affected = [];
  for (const gate of gates) {
    const reasons = gateMatchesPaths(gate, changedPaths);
    if (reasons.length > 0) affected.push({ gate, reasons });
  }

  if (affected.length === 0) {
    console.log('  No gates affected by this diff.');
    return;
  }
  for (const a of affected) {
    console.log(`  ${a.gate.id}: ${a.gate.title ?? ''}`);
    for (const r of a.reasons) console.log(`    - ${r}`);
  }
  console.log('');
  console.log(`Run \`node scripts/governance/run.mjs --gate <id> --mode warn\` per gate to see the actual findings,`);
  console.log(`or \`--suggest-changeset\` to auto-author stub changesets for predicted failures.`);
}
