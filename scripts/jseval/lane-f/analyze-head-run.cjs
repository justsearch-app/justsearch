// Lane F: summarise one head-flag-run.sh output directory (GC pauses from -Xlog, working set per
// phase, search latency, startup and agent-turn timings). Origin: 917 Derisk 1 (tmp/analyze-head-baseline.cjs).
// Usage: node scripts/jseval/lane-f/analyze-head-run.cjs <outdir>
const fs = require('fs');
const path = require('path');
const dir = process.argv[2] || 'tmp';
const phasesPath = path.join(dir, 'phases.json');
const phases = fs.existsSync(phasesPath) ? JSON.parse(fs.readFileSync(phasesPath, 'utf8')) : {};
for (const f of ['first-startup.txt', 'restart-startup.txt', 'agent-turn.txt']) {
  const fp = path.join(dir, f);
  if (fs.existsSync(fp)) console.log(`- ${f}: ${fs.readFileSync(fp, 'utf8').trim()}`);
}

function pct(arr, p) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(p * s.length))];
}
function fmt(x, d = 1) { return x == null ? '-' : Number(x).toFixed(d); }

// ---- GC logs (first launch, warm restart) ----
// The `[time]` decoration is optional: -Xlog with only `file=` emits uptime,level,tags.
const re = /^(?:\[[^\]]+\])?\[([0-9.]+)s\]\[info\]\[gc\s*\] GC\((\d+)\) Pause (Young|Full) \(([^)]+)\) (\d+)M->(\d+)M\((\d+)M\) ([0-9.]+)ms/;
function gcReport(file, title) {
  const fp = path.join(dir, file);
  if (!fs.existsSync(fp)) return;
  const gc = fs.readFileSync(fp, 'utf8').split('\n');
  const pauses = [];
  for (const line of gc) {
    const m = re.exec(line);
    if (m) pauses.push({ up: Number(m[1]), id: Number(m[2]), kind: m[3], cause: m[4], before: +m[5], after: +m[6], cap: +m[7], ms: Number(m[8]) });
  }
  const young = pauses.filter((p) => p.kind === 'Young').map((p) => p.ms);
  const full = pauses.filter((p) => p.kind === 'Full');
  console.log(`\n# GC (${file}, ${title})`);
  console.log(`- uptime covered: ${fmt(pauses.length ? pauses[pauses.length - 1].up : 0, 0)} s; pauses: ${pauses.length} (young ${young.length}, full ${full.length})`);
  console.log(`- young pause ms: p50 ${fmt(pct(young, 0.5))} · p95 ${fmt(pct(young, 0.95))} · max ${fmt(Math.max(0, ...young))} · total ${fmt(young.reduce((a, b) => a + b, 0), 0)}`);
  console.log(`- full pauses: ${full.map((p) => `${p.cause} ${p.before}M->${p.after}M ${fmt(p.ms)}ms @${fmt(p.up, 0)}s`).join('; ') || 'none'}`);
  const causes = {};
  for (const p of pauses) causes[p.cause] = (causes[p.cause] || 0) + 1;
  console.log(`- causes: ${JSON.stringify(causes)}`);
  if (pauses.length) {
    const heapAfter = pauses.map((p) => p.after);
    console.log(`- live heap after GC (M): min ${Math.min(...heapAfter)} · max ${Math.max(...heapAfter)} · last ${heapAfter[heapAfter.length - 1]} of ${pauses[pauses.length - 1].cap}M committed`);
  }
  const safepoints = gc.filter((l) => /safepoint/.test(l) && /Total time for which application threads were stopped/.test(l));
  const spMs = safepoints.map((l) => Number((/stopped: ([0-9.]+) seconds/.exec(l) || [])[1]) * 1000).filter((x) => Number.isFinite(x));
  if (spMs.length) console.log(`- safepoints: ${spMs.length}, total ${fmt(spMs.reduce((a, b) => a + b, 0), 0)} ms, max ${fmt(Math.max(...spMs))} ms`);
  const codeCache = gc.filter((l) => /CodeCache|code cache/i.test(l)).length;
  const meta = gc.filter((l) => /Metaspace/.test(l)).length;
  console.log(`- lines mentioning CodeCache: ${codeCache}; Metaspace: ${meta}`);
}
gcReport('head-gc.log', 'first launch');
gcReport('head-gc-2.log', 'warm restart');

// ---- RSS samples ----
const csv = fs.readFileSync(path.join(dir, 'head-rss.csv'), 'utf8').trim().split('\n').slice(1).map((l) => l.split(','));
const rows = csv.map(([ts, role, pid, ws, pm, cpu, thr]) => ({ t: new Date(ts).getTime(), role, ws: +ws, pm: +pm, cpu: +cpu, thr: +thr }));
console.log('\n# Working set (MB) per role');
function summar(role, from, to) {
  const r = rows.filter((x) => x.role === role && (!from || x.t >= from) && (!to || x.t <= to)).map((x) => x.ws);
  if (!r.length) return '-';
  return `n=${r.length} min ${fmt(Math.min(...r), 0)} · p50 ${fmt(pct(r, 0.5), 0)} · max ${fmt(Math.max(...r), 0)}`;
}
const names = Object.keys(phases);
if (!names.length) {
  for (const role of ['head', 'worker']) console.log(`- ${role} (all samples): ${summar(role)}`);
} else {
  for (const n of names) {
    const [a, b] = phases[n].map((x) => new Date(x).getTime());
    console.log(`- ${n}: head ${summar('head', a, b)} | worker ${summar('worker', a, b)}`);
  }
}
const first = rows.find((x) => x.role === 'head'), last = [...rows].reverse().find((x) => x.role === 'head');
if (first && last) console.log(`- head threads first/last: ${first.thr}/${last.thr}; head CPU seconds consumed over window: ${fmt(last.cpu - first.cpu, 0)}`);

// ---- search latency ----
console.log('\n# Search latency (POST /api/knowledge/search, sequential, ms; grouped by effectiveMode when recorded)');
for (const f of fs.readdirSync(dir).filter((f) => /^search-load.*\.csv$/.test(f))) {
  const rows = fs.readFileSync(path.join(dir, f), 'utf8').trim().split('\n').slice(1).map((l) => l.split(',')).filter((c) => c[2] === '200');
  const groups = {};
  for (const c of rows) (groups[c[3] || '?'] ??= []).push(+c[1]);
  const all = rows.map((c) => +c[1]);
  console.log(`- ${f}: n=${all.length} p50 ${fmt(pct(all, 0.5), 0)} · p95 ${fmt(pct(all, 0.95), 0)} · max ${fmt(Math.max(...all), 0)}`);
  for (const [mode, ms] of Object.entries(groups)) {
    if (Object.keys(groups).length > 1 || mode !== '?') console.log(`    ${mode}: n=${ms.length} p50 ${fmt(pct(ms, 0.5), 0)} · p95 ${fmt(pct(ms, 0.95), 0)} · max ${fmt(Math.max(...ms), 0)}`);
  }
}
