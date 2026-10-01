// Lane F: retained identifiers and historical descriptions must be labelled.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const terms = [
  'MMF', 'heartbeat', 'suicide', 'breath', 'OFFSET_', 'WorkerSpawner', // lane F residue vocabulary
  'ORDINAL_WORKER_SNAPSHOT', 'WORKER_FORWARDED_PROPS', 'MainSignalBus', // lane F residue vocabulary
  'MmfWorkerSignalBus', 'MmfWorkerSignalLayoutV1', 'WorkerLivenessDecision', // lane F residue vocabulary
  'main_gpu_active', 'ForegroundLoadInterceptor', 'WorkerProcessManager', // lane F residue vocabulary
  'RemoteKnowledgeClient', 'worker.log', 'head.log', 'core.worker-log', // lane F residue vocabulary
  'core.head-log', 'Head/Worker', 'two JVMs', 'io.grpc', // lane F residue vocabulary
  'worker-config-snapshot', 'restart-worker', 'Worker restart', 'WORKER_', // lane F residue vocabulary
  'owner: "WORKER"', 'Worker process', 'Worker JVM', // lane F residue vocabulary
];
const label = /\b(?:historical|retired|superseded|lane F|[A-F]\d+(?:-\d+)?|no longer|replaced)\b/i;
const gitHistory = /\bgit\s+(?:show|log|blame)\b|\b(?:commit|revision)\s+`?[0-9a-f]{7,40}\b|\b[0-9a-f]{7,40}:\S+/i;

// A retiredNote labels its containing JSON object, not adjacent live entries.
function retiredRanges(text) {
  const stack = [];
  const ranges = [];
  let quoted = false;
  let escaped = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (escaped) escaped = false;
      else if (c === '\\') escaped = true;
      else if (c === '"') quoted = false;
      continue;
    }
    if (c === '"') {
      if (text.slice(i).match(/^"retiredNote"\s*:/) && stack.length) stack.at(-1).retired = true;
      quoted = true;
    } else if (c === '{') stack.push({ start: i, retired: false });
    else if (c === '}') {
      const object = stack.pop();
      if (object?.retired) ranges.push([object.start, i]);
    }
  }
  return ranges;
}

const allowlistPath = 'governance/lane-f-residue-allowlist.v1.json';

export function validateAllowlist(data) {
  if (data.version !== 1 || !Array.isArray(data.entries)) throw new Error('Invalid residue allowlist version/entries');
  const keys = new Set();
  for (const entry of data.entries) {
    if (typeof entry.path !== 'string' || !entry.path || /[*?\[\]{}:]/.test(entry.path)
        || entry.path.startsWith('/') || entry.path.includes('..')
        || entry.path.includes('\\') || !terms.concat('gRPC', 'SupervisionPolicy').includes(entry.term) // lane F vocabulary
        || !['b', 'c', 'd'].includes(entry.category) || typeof entry.reason !== 'string'
        || !entry.reason.trim() || (entry.category === 'b' && !entry.reason.includes('C2-1'))
        || !Number.isSafeInteger(entry.occurrenceCount) || entry.occurrenceCount < 1
        || !Array.isArray(entry.anchors) || entry.anchors.length !== entry.occurrenceCount
        || entry.anchors.some(anchor => typeof anchor !== 'string' || !anchor.trim()
          || anchor !== anchor.trim() || /[\r\n]/.test(anchor) || !anchor.includes(entry.term))) {
      throw new Error(`Invalid residue allowlist entry: ${JSON.stringify(entry)}`);
    }
    const key = `${entry.path}\0${entry.term}`;
    if (keys.has(key)) throw new Error(`Duplicate residue allowlist entry: ${entry.path} / ${entry.term}`);
    keys.add(key);
  }
  return data.entries;
}

// One candidate per unlabelled term occurrence; shared by review projections and enforcement.
export function findCandidates(file, text) {
  file = file.replaceAll('\\', '/');
  const basename = path.posix.basename(file);
  const legacyFixture = /legacy/i.test(basename)
    && (/(?:^|\/)(?:test|tests|fixtures?)(?:\/|$)/i.test(file)
      || /(?:^|[-_.])(?:tests?|fixtures?)(?:[-_.]|$)/i.test(basename)
      || /(?:Test|Fixture)s?(?:[A-Z._-]|$)/.test(basename));
  if (file === allowlistPath || /^docs\/(?:tempdocs\/|design\/lane-f-engine-jvm\/)/.test(file) || legacyFixture) return [];
  const historicalDecision = /^docs\/decisions\/00(?:01|02)[^/]*\.md$/.test(file);
  const frontmatter = /^---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/.exec(text);
  const decisionBodyStart = frontmatter?.[0].length ?? 0;
  const headings = [];
  const ranges = file.endsWith('.json') ? retiredRanges(text) : [];
  const hits = [];
  let offset = 0;
  let fence = null;
  for (const [index, line] of text.split('\n').entries()) {
    const delimiter = file.endsWith('.md') && /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
    if (delimiter) {
      if (!fence) fence = delimiter[1];
      else if (delimiter[1][0] === fence[0] && delimiter[1].length >= fence.length
          && /^\s*$/.test(delimiter[2])) fence = null;
    }
    const heading = file.endsWith('.md') && !fence && /^ {0,3}(#{1,6})\s+(.+)$/.exec(line);
    if (heading) {
      while (headings.length && headings.at(-1).level >= heading[1].length) headings.pop();
      headings.push({ level: heading[1].length, labelled: label.test(heading[2]) });
    }
    const candidates = [...terms];
    const currentLine = line.replaceAll('EngineSupervisionPolicy', ' '.repeat(23)).replaceAll('BrainSupervisionPolicy', ' '.repeat(22)); // lane F current-contract rule
    if (!label.test(line)) candidates.push('SupervisionPolicy', 'gRPC'); // lane F current-contract rule
    const positions = candidates.flatMap(term => {
      const positionsForTerm = [];
      for (let from = 0, index; (index = currentLine.indexOf(term, from)) !== -1; from = index + term.length) positionsForTerm.push(offset + index);
      return positionsForTerm.map(position => ({ term, position }));
    });
    if (positions.length && !label.test(line) && !gitHistory.test(line)
        && !headings.some(h => h.labelled)
        && !(historicalDecision && offset >= decisionBodyStart)) {
      const unlabelled = positions.filter(({ position }) => !ranges.some(([start, end]) => position >= start && position <= end));
      for (const { term } of unlabelled) {
        hits.push({ term, anchor: line.trim(), line: index + 1, text: line.trimEnd() });
      }
    }
    offset += line.length + 1;
  }
  return hits;
}

export function findResidue(file, text, allowlist = [], matched = new Map()) {
  file = file.replaceAll('\\', '/');
  const entries = new Map(allowlist.filter(entry => entry.path === file).map(entry => [entry.term, entry]));
  const unresolved = new Map();
  for (const hit of findCandidates(file, text)) {
    const entry = entries.get(hit.term);
    if (entry) {
      const consumed = matched.get(entry) ?? new Map();
      matched.set(entry, consumed);
      const count = consumed.get(hit.anchor) ?? 0;
      const reviewed = entry.anchors.filter(anchor => anchor === hit.anchor).length;
      if (count < reviewed) {
        consumed.set(hit.anchor, count + 1);
        continue;
      }
    }
    const previous = unresolved.get(hit.line);
    unresolved.set(hit.line, {
      text: `${file}:${hit.line}: ${hit.text}`,
      unanchored: Boolean(entry) || previous?.unanchored,
    });
  }
  return [...unresolved.values()].map(hit => (hit.unanchored ? 'UNANCHORED allowlist: ' : '') + hit.text);
}

export function staleAllowlist(allowlist, matched) {
  return allowlist.flatMap(entry => {
    const consumed = matched.get(entry);
    const count = consumed ? [...consumed.values()].reduce((sum, value) => sum + value, 0) : 0;
    return count === entry.occurrenceCount ? []
      : [`STALE allowlist: ${entry.path} / ${entry.term}: matched ${count}/${entry.occurrenceCount} reviewed occurrences: ${entry.reason}`];
  });
}

function main(args) {
  let root = process.cwd();
  let paths = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--root') root = path.resolve(args[++i]);
    else if (args[i] === '--paths') {
      while (i + 1 < args.length && !args[i + 1].startsWith('--')) paths.push(args[++i].replaceAll('\\', '/'));
    } else throw new Error(`Unknown argument: ${args[i]}`);
  }
  function walk(directory, prefix = '') {
    return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
      if (['.git', 'node_modules', 'build', '.gradle'].includes(entry.name)) return [];
      const relative = prefix + entry.name;
      if (entry.isSymbolicLink()) return [];
      return entry.isDirectory() ? walk(path.join(directory, entry.name), relative + '/') : [relative];
    });
  }
  // Include pending additions in the worktree; omit ignored build/dependency artifacts.
  let files;
  try {
    files = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
  } catch {
    files = walk(root);
  }
  const absoluteAllowlist = path.join(root, allowlistPath);
  const allowlist = fs.existsSync(absoluteAllowlist)
    ? validateAllowlist(JSON.parse(fs.readFileSync(absoluteAllowlist, 'utf8'))) : [];
  const matched = new Map();
  const hits = [];
  // Scan every file even for --paths, so stale entries cannot hide behind a scoped run.
  for (const file of [...new Set(files)].sort()) {
    const absolute = path.join(root, file);
    if (!fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) continue;
    const bytes = fs.readFileSync(absolute);
    if (bytes.includes(0)) continue;
    const found = findResidue(file, bytes.toString('utf8'), allowlist, matched);
    const inScope = !paths.length || paths.some(p => file === p || file.startsWith(p.replace(/\/$/, '') + '/'));
    hits.push(...found.filter(hit => inScope || hit.startsWith('UNANCHORED allowlist:')));
  }
  hits.push(...staleAllowlist(allowlist, matched));
  for (const hit of hits) console.log(hit);
  process.exitCode = hits.length ? 1 : 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main(process.argv.slice(2));
