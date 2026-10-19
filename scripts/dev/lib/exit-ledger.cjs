'use strict';

/**
 * Exit ledger format v1 for Node (ADR-0051; Spec B §3.3-§4, Spec F step 5).
 *
 * The same format as the Java ledger in `io.justsearch.app.api.runtime.ledger` (ExitLedger,
 * ExitLedgerReader, ExitLedgerWriter, LedgerTimestamps): append-only JSON lines under
 * `<dataDir>/runtime/exit-ledger/`, one record per line, recorded by the owner of a process before it
 * acts on it. The line schema is `governance/exit-ledger.v1.schema.json`.
 *
 * Every rule here mirrors the Java one, line for line where it matters, because the three readers
 * (Java, Node, Rust) must give the same answer on the same bytes: the shared fixtures under
 * `governance/fixtures/exit-ledger/cases/` carry an `expected-summary.json` that each language's test
 * reproduces (Spec B T15).
 *
 * - A timestamp is known only if it is an ISO-8601 UTC string ending in `Z` at or after
 *   2001-01-01T00:00:00Z. `null`, numbers (`0`, FILETIME 0 as −11644473600000), and anything earlier
 *   read as unknown. Readers never compute an exit class from a timestamp.
 * - The reader tolerates everything a crash or a newer writer can leave: a torn last line, CRLF,
 *   blank lines, corrupt lines, newer schema versions, unknown types, missing files.
 * - The writer opens the file per append, writes the whole line and fsyncs it; a failure never
 *   throws to the caller, it returns false and queues a `ledger-gap` record for the next append.
 */

const fs = require('node:fs');
const processIdentity = require('./process-identity.cjs');
const path = require('node:path');

const SCHEMA_VERSION = 1;
/** The largest record a writer appends, newline included. */
const MAX_RECORD_BYTES = 4 * 1024;
/** Readers skip any line longer than this as corrupt. */
const MAX_READ_LINE_BYTES = 8 * 1024;
/** A rotating writer rotates at session start when the file is larger than this. */
const SESSION_ROTATE_BYTES = 1024 * 1024;
/** A rotating writer rotates before an append that would take the file past this. */
const HARD_CAP_BYTES = 2 * 1024 * 1024;

/** Process exit codes our own terminate calls use (Spec F §0.1; TerminateCodes.java). */
const JS_CHILD_STOP = 0x4A530001;
const JS_ENGINE_STOP = 0x4A530002;

/** The earliest known timestamp, epoch ms of 2001-01-01T00:00:00Z. */
const EARLIEST_KNOWN_MS = Date.UTC(2001, 0, 1);

/**
 * The clocks a process birth (Windows FILETIME, 100 ns ticks) is read from, as the ticks each one
 * resolves: GetProcessTimes (the Engine's ledger records, a held process handle) is exact;
 * Win32_Process.CreationDate (the dev runner's records, the process collector) truncates to the
 * microsecond; Java `ProcessHandle.Info.startInstant` (the manifest `startedAt`) to the millisecond.
 */
const BIRTH_TICKS = Object.freeze({ EXACT: 1n, MICROSECOND: 10n, MILLISECOND: 10000n });

/**
 * The one birth-identity rule every reader uses (Spec F §6.1). Two births name the same process when
 * they agree at the coarser precision of their two clocks: both truncated to `tick` are equal. The
 * default is the microsecond, the coarsest clock a FILETIME birth is recorded from (CIM), so an exact
 * birth (…062) and the CIM read of the same process (…060) match, and two exact births match as
 * exactly as CIM can tell them apart. A reader whose value is a millisecond instant passes
 * MILLISECOND.
 *
 * PID reuse cannot pass this rule: a pid names another process only after the recorded one has
 * exited and its last handle closed, so the new process is born a whole process lifetime after the
 * recorded birth, never inside the same tick. Each reader also compares the pid (and the executable
 * where it has one).
 */
function sameBirth(a, b, tick = BIRTH_TICKS.MICROSECOND) {
  const ok = (v) => typeof v === 'string' && /^[1-9][0-9]{0,19}$/.test(v);
  if (!ok(a) || !ok(b) || typeof tick !== 'bigint' || tick < 1n) return false;
  return BigInt(a) / tick === BigInt(b) / tick;
}

const FAMILIES = Object.freeze({
  children: Object.freeze({ current: 'children.v1.jsonl', rotated: 'children.v1.1.jsonl' }),
  engine: Object.freeze({ current: 'engine.v1.jsonl', rotated: 'engine.v1.1.jsonl' }),
});

/** Record type token -> family, or null for a type of every family (LedgerRecordType). */
const RECORD_TYPES = Object.freeze({
  'child-spawn': 'children',
  'child-start': 'children',
  'stop-intent': 'children',
  'stop-escalate': 'children',
  'child-exit': 'children',
  'child-survived': 'children',
  'gpu-release': 'children',
  'child-note': 'children',
  'owner-died': 'children',
  'job-process-start': 'children',
  'job-process-exit': 'children',
  'helper-start': 'children',
  'helper-intent': 'children',
  'helper-exit': 'children',
  'helper-window-start': 'children',
  'helper-window-end': 'children',
  'engine-start': 'engine',
  'engine-intent': 'engine',
  'engine-self-intent': 'engine',
  'engine-exit': 'engine',
  'engine-owner-died': 'engine',
  'ledger-gap': null,
});

const WRITER_ROLES = Object.freeze(['engine', 'shell', 'dev-runner', 'dev-cli']);

/** In LedgerGapReason declaration order, which is also the order pending gaps are written in. */
const GAP_REASONS = Object.freeze(['WRITE_FAILED', 'ROTATE_FAILED', 'CORRUPT_LINES', 'EVIDENCE_LOST', 'UNKNOWN_SCHEMA', 'FORCE_FAILED']);

const ENVELOPE = new Set(['schemaVersion', 'type', 'seq', 'at', 'writer']);

/** Record fields that hold an instant; the summary reads each through the 2001 rule. */
const TIME_FIELDS = Object.freeze([
  'processStartedAt', 'requestedAt', 'deadlineAt', 'exitedAt', 'observedAt', 'terminateCalledAt',
  'cooperativeAt', 'since',
]);

const repoRoot = path.resolve(__dirname, '..', '..', '..');
const schemaPath = path.join(repoRoot, 'governance', 'exit-ledger.v1.schema.json');

let stopDetailTokens = null;
/**
 * The StopDetail wire tokens, read from the schema's `stopDetailOrNull` enum (the one list the Java
 * enum is checked against by ExitLedgerSchemaTest), so there is no second hand-kept list here.
 */
function knownStopDetails() {
  if (!stopDetailTokens) {
    const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));
    stopDetailTokens = new Set(schema.$defs.stopDetailOrNull.enum.filter((t) => typeof t === 'string'));
  }
  return stopDetailTokens;
}

/** `<dataDir>/runtime/exit-ledger` (LedgerFamily.directory). */
function ledgerDirectory(dataDir) {
  return path.join(dataDir, 'runtime', 'exit-ledger');
}

function familyOf(family) {
  const f = FAMILIES[family];
  if (!f) throw new TypeError(`unknown ledger family: ${family}`);
  return f;
}

function currentPath(ledgerDir, family) {
  return path.join(ledgerDir, familyOf(family).current);
}

function rotatedPath(ledgerDir, family) {
  return path.join(ledgerDir, familyOf(family).rotated);
}

function belongsTo(type, family) {
  if (!Object.prototype.hasOwnProperty.call(RECORD_TYPES, type)) return false;
  const owner = RECORD_TYPES[type];
  return owner === null || owner === family;
}

// ---------------------------------------------------------------------------------------------
// Timestamps (LedgerTimestamps)
// ---------------------------------------------------------------------------------------------

const INSTANT_RE = /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/;

function daysInMonth(year, month) {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

/**
 * Epoch ms (truncated to the millisecond) of a wire timestamp, or null when it is unknown: not a
 * string, not ISO-8601 UTC ending in `Z`, not a real calendar instant, or before 2001.
 */
function knownTimestamp(value) {
  if (typeof value !== 'string' || !value.endsWith('Z')) return null;
  const m = INSTANT_RE.exec(value);
  if (!m) return null;
  const [year, month, day] = m.slice(1, 4).map(Number);
  let [hour, minute, second] = m.slice(4, 7).map(Number);
  const fraction = m[7] ?? '';
  if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) return null;
  // java.time's instant parser: 24:00:00 is the next midnight, 23:59:60 reads as 23:59:59.
  let extraDays = 0;
  if (hour === 24 && minute === 0 && second === 0 && !/[1-9]/.test(fraction)) {
    hour = 0;
    extraDays = 1;
  } else if (hour === 23 && minute === 59 && second === 60) {
    second = 59;
  }
  if (hour > 23 || minute > 59 || second > 59) return null;
  const millis = fraction ? Number(fraction.padEnd(3, '0').slice(0, 3)) : 0;
  const ms = Date.UTC(year, month - 1, day + extraDays, hour, minute, second, millis);
  // Date.UTC maps years 0-99 to 1900-1999; such a year is before 2001 either way.
  if (!Number.isFinite(ms) || year < 100 || ms < EARLIEST_KNOWN_MS) return null;
  return ms;
}

/** The wire form: ISO-8601 UTC at millisecond precision, the fraction omitted when zero. */
function formatTimestamp(ms) {
  const iso = new Date(ms instanceof Date ? ms.getTime() : ms).toISOString();
  return iso.endsWith('.000Z') ? `${iso.slice(0, -5)}Z` : iso;
}

// ---------------------------------------------------------------------------------------------
// Reader (ExitLedgerReader)
// ---------------------------------------------------------------------------------------------

/** JSON.parse that keeps, per number, whether its source text was an integer literal. */
function parseLine(text) {
  const integral = new WeakMap();
  const value = JSON.parse(text, function reviver(key, val, context) {
    if (typeof val === 'number' && context && typeof context.source === 'string'
        && /^-?\d+$/.test(context.source)) {
      let marks = integral.get(this);
      if (!marks) { marks = new Set(); integral.set(this, marks); }
      marks.add(key);
    }
    return val;
  });
  const isIntegral = (holder, key) => typeof holder?.[key] === 'number'
    && integral.get(holder)?.has(key) === true;
  return { value, isIntegral };
}

function readWriter(node, isIntegral) {
  if (node === null || typeof node !== 'object' || Array.isArray(node)) return null;
  const { role, pid, startedAt } = node;
  if (typeof role !== 'string' || !isIntegral(node, 'pid') || pid <= 0 || typeof startedAt !== 'string') {
    return null;
  }
  if (!WRITER_ROLES.includes(role)) return null;
  const started = knownTimestamp(startedAt);
  if (started === null) return null;
  const native = node.processIdentity == null ? null : processIdentity.linuxIdentity(node.processIdentity);
  if (node.processIdentity != null && (!native || native.pid !== pid)) return null;
  return { role, pid, startedAt: started, ...(native ? { processIdentity: native } : {}) };
}

/** Java's String.isBlank (Character.isWhitespace), which differs from JavaScript's \s. */
const JAVA_WHITESPACE = new Set([
  0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x1680,
  0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2008, 0x2009, 0x200a,
  0x2028, 0x2029, 0x205f, 0x3000,
]);

function isJavaBlank(text) {
  for (const ch of text) if (!JAVA_WHITESPACE.has(ch.codePointAt(0))) return false;
  return true;
}

function readLine(text, family, out, tally) {
  if (isJavaBlank(text)) return;
  let parsed;
  try {
    parsed = parseLine(text);
  } catch {
    tally.corruptLines += 1;
    return;
  }
  const { value: line, isIntegral } = parsed;
  if (line === null || typeof line !== 'object' || Array.isArray(line)) {
    tally.corruptLines += 1;
    return;
  }
  if (!isIntegral(line, 'schemaVersion') || line.schemaVersion < 1) {
    tally.corruptLines += 1;
    return;
  }
  if (line.schemaVersion > SCHEMA_VERSION) {
    tally.unknownSchemaLines += 1;
    return;
  }
  if (typeof line.type !== 'string') {
    tally.corruptLines += 1;
    return;
  }
  if (!belongsTo(line.type, family)) {
    tally.unknownTypeLines += 1;
    return;
  }
  const writer = readWriter(line.writer, isIntegral);
  if (!isIntegral(line, 'seq') || line.seq < 1 || !writer) {
    tally.corruptLines += 1;
    return;
  }
  out.push({
    schemaVersion: SCHEMA_VERSION,
    type: line.type,
    seq: line.seq,
    at: typeof line.at === 'string' ? knownTimestamp(line.at) : null,
    writer,
    line,
  });
}

function readFileBytes(bytes, family, out, tally) {
  let start = 0;
  for (let i = 0; i < bytes.length; i += 1) {
    if (bytes[i] !== 0x0a) continue;
    const end = i > start && bytes[i - 1] === 0x0d ? i - 1 : i;
    const length = end - start;
    if (length > 0) {
      if (length > MAX_READ_LINE_BYTES) {
        tally.corruptLines += 1;
      } else {
        readLine(bytes.toString('utf8', start, end), family, out, tally);
      }
    }
    start = i + 1;
  }
  if (start < bytes.length) tally.tornTail = true;
}

/**
 * Reads one family under `ledgerDir`, the older generation first. Missing files are an empty ledger.
 * An I/O error on a file that exists is thrown: the caller must treat the ledger as unreadable.
 *
 * `files` has the same counts for each file read, in that order.
 *
 * @returns {{records: object[], corruptLines: number, unknownSchemaLines: number,
 *   unknownTypeLines: number, tornTail: boolean, skippedLines: number, files: object[]}}
 */
function readLedger(ledgerDir, family) {
  familyOf(family);
  const records = [];
  const files = [];
  for (const file of ledgerFiles(ledgerDir, family)) {
    let bytes;
    try {
      bytes = fs.readFileSync(file);
    } catch (err) {
      if (err && err.code === 'ENOENT') continue;
      throw err;
    }
    const tally = { corruptLines: 0, unknownSchemaLines: 0, unknownTypeLines: 0, tornTail: false };
    readFileBytes(bytes, family, records, tally);
    files.push({ file, ...tally });
  }
  return { records, ...totalOf(files), files };
}

/** The counts of a family's files added up, as readLedger reports them. */
function totalOf(files) {
  const total = { corruptLines: 0, unknownSchemaLines: 0, unknownTypeLines: 0, tornTail: false };
  for (const f of files) {
    total.corruptLines += f.corruptLines;
    total.unknownSchemaLines += f.unknownSchemaLines;
    total.unknownTypeLines += f.unknownTypeLines;
    total.tornTail = total.tornTail || f.tornTail;
  }
  return { ...total, skippedLines: total.corruptLines + total.unknownSchemaLines + total.unknownTypeLines };
}

/** How many times a read starts again when a rotation moves the files under it. */
const STABLE_READ_ATTEMPTS = 8;
/** How long a read waits before starting again after it met a file moving (a rotation in progress). */
const UNSETTLED_PAUSE_MS = 5;

/**
 * readLedger for a caller that reads the same ledger more than once (a stop reads it before each of
 * its phases, and its census when it arms and at its verdict). Each file is known by its file id
 * (which a rotation's rename keeps), size and modification time. A file unchanged since the last
 * read is not opened again; a file that grew is read from where the last read ended, so only its
 * new tail is read and parsed, and a line still being written then (a torn tail) is parsed whole
 * once it is complete; any other change parses the file again from its start. A line of the tail
 * that cannot be read (a corrupt append, or the tail of a file rewritten in place under the same id,
 * which starts mid-line) is counted damaged like any other, and what was read before stays: the
 * tail is never a reason to read the whole file again. The result is readLedger's, but for such a
 * rewrite, which it reports as damage. `version()` changes whenever a read finds anything different.
 *
 * What is read is known by the handle it is read through, never by the stat before the open: a
 * rotation between the two (the path now names the replacement file) shows as a different file id
 * on the handle. Then neither file's cached records are kept, and the read starts again from the
 * listing, so the moved file is read in full under its new name and the replacement in full under
 * the old. A read that ends with a listing or a file id other than the one it started from starts
 * again too, and so does one that meets a listed file missing at its stat or its open (renamed, not
 * yet replaced), or a file shorter than its handle said when read (truncated). A read never lets what it
 * cannot place stand for the ledger: a family still moving after STABLE_READ_ATTEMPTS reads (a short
 * pause between them) throws, and the caller treats that family as unreadable.
 */
function createIncrementalReader() {
  const cache = new Map();
  const seen = new Map();
  let version = 0;
  const readAt = (fd, position, length) => {
    const bytes = Buffer.alloc(length);
    let done = 0;
    while (done < length) {
      const n = fs.readSync(fd, bytes, done, length - done, position + done);
      if (n === 0) break;
      done += n;
    }
    return done === length ? bytes : bytes.subarray(0, done);
  };
  // A file id, or null where the file system has none (then nothing about the file is cached).
  const idOf = (stat) => (stat.ino ? `${stat.dev}:${stat.ino}` : null);
  // A file a rotation is moving: gone (renamed, not yet replaced) or, on Windows, being deleted or
  // renamed under a handle that does not share that (EPERM, EBUSY, EACCES).
  const moving = (err) => ['ENOENT', 'EPERM', 'EBUSY', 'EACCES'].includes(err?.code);
  const statOf = (file) => {
    try {
      return fs.statSync(file, { bigint: true });
    } catch (err) {
      if (moving(err)) return null;
      throw err;
    }
  };
  // A listed file opened, or null when a rotation is moving it.
  const openOf = (file) => {
    try {
      return fs.openSync(file, 'r');
    } catch (err) {
      if (moving(err)) return null;
      throw err;
    }
  };
  const pauseSync = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
  const fresh = (family) => ({ family, size: 0, mtimeNs: null, offset: 0, records: [],
    tally: { corruptLines: 0, unknownSchemaLines: 0, unknownTypeLines: 0, tornTail: false } });
  const unchanged = (entry, family, size, mtimeNs) => Boolean(entry) && entry.family === family
    && entry.size === size && entry.mtimeNs === mtimeNs;

  // One read of the family's files, or null when a rotation moved them during it.
  function readOnce(ledgerDir, family) {
    const listed = ledgerFiles(ledgerDir, family);
    const files = [];
    const records = [];
    const read = [];
    for (const file of listed) {
      const stat = statOf(file);
      if (!stat) return null;
      const id = idOf(stat);
      let entry = id ? cache.get(id) : null;
      let size = Number(stat.size), mtimeNs = stat.mtimeNs;
      if (!unchanged(entry, family, size, mtimeNs)) {
        const fd = openOf(file);
        if (fd === null) return null;
        try {
          const held = fs.fstatSync(fd, { bigint: true });
          if (idOf(held) !== id) {
            // The path names another file than the one stat saw: a rotation. Both are read again.
            if (id) cache.delete(id);
            if (idOf(held)) cache.delete(idOf(held));
            return null;
          }
          size = Number(held.size);
          mtimeNs = held.mtimeNs;
          if (!unchanged(entry, family, size, mtimeNs)) {
            // Only a file that grew keeps what was parsed; anything else is parsed from its start.
            const grew = Boolean(entry) && entry.family === family && size > entry.size;
            if (!grew) entry = fresh(family);
            // Every early return below drops the entry from the cache, so nothing half-read is kept.
            if (size > entry.offset) {
              const chunk = readAt(fd, entry.offset, size - entry.offset);
              if (chunk.length < size - entry.offset) {
                // Shorter than its handle said: truncated while read.
                if (id) cache.delete(id);
                return null;
              }
              const complete = chunk.lastIndexOf(0x0a) + 1;
              if (complete > 0) {
                // Only the tail is read. A line of it that cannot be read (a corrupt append, or a
                // file rewritten in place so that the tail starts mid-line) is counted damaged, a
                // gap; what was read before it stays as it was read.
                readFileBytes(chunk.subarray(0, complete), family, entry.records, entry.tally);
                entry.offset += complete;
              }
            }
            entry.size = size;
            entry.mtimeNs = mtimeNs;
            entry.tally.tornTail = size > entry.offset;
            if (id) cache.set(id, entry);
          }
        } finally {
          fs.closeSync(fd);
        }
      }
      for (const r of entry.records) records.push(r);
      files.push({ file, ...entry.tally });
      read.push({ file, id, size, mtimeNs });
    }
    // Nothing moved while the files were read: the same files, under the same names.
    const now = ledgerFiles(ledgerDir, family);
    if (now.length !== listed.length || now.some((file, i) => file !== listed[i])) return null;
    for (const r of read) {
      const stat = statOf(r.file);
      if (!stat || idOf(stat) !== r.id) return null;
    }
    return { records, ...totalOf(files), files,
      signature: read.map((r) => `${r.file}|${r.id}|${r.size}|${r.mtimeNs}`).join('\n') };
  }

  function read(ledgerDir, family) {
    familyOf(family);
    const key = `${ledgerDir}|${family}`;
    for (let attempt = 0; attempt < STABLE_READ_ATTEMPTS; attempt++) {
      if (attempt > 0) pauseSync(UNSETTLED_PAUSE_MS);
      const out = readOnce(ledgerDir, family);
      if (!out) continue;
      const { signature, ...result } = out;
      if (seen.get(key) !== signature) {
        seen.set(key, signature);
        version += 1;
      }
      return result;
    }
    seen.delete(key);
    version += 1;
    const err = new Error(`${family} ledger files still moving (or not readable) after ${STABLE_READ_ATTEMPTS} reads; not read`);
    err.code = 'LEDGER_UNSETTLED';
    throw err;
  }
  return { read, version: () => version };
}

/** A record's string field, or null when absent, null or not a string (LedgerRecord.text). */
function textField(record, name) {
  const v = record?.line?.[name];
  return typeof v === 'string' ? v : null;
}

/** The stop detail read tolerantly: a known token, `unknown` for any other string, else null. */
function stopDetailOf(record) {
  const raw = textField(record, 'detail');
  if (raw === null) return null;
  return knownStopDetails().has(raw) ? raw : 'unknown';
}

/**
 * The canonical summary of one family, the shape every language's test reproduces from the shared
 * fixtures (Spec B T15). Instants are the known ones only, in the wire form; anything else is null.
 */
function summarizeLedger(ledgerDir, family) {
  const read = readLedger(ledgerDir, family);
  return {
    family,
    records: read.records.map((r) => {
      const times = {};
      for (const name of TIME_FIELDS) {
        if (!Object.prototype.hasOwnProperty.call(r.line, name)) continue;
        const ms = knownTimestamp(r.line[name]);
        times[name] = ms === null ? null : formatTimestamp(ms);
      }
      return {
        type: r.type,
        seq: r.seq,
        at: r.at === null ? null : formatTimestamp(r.at),
        writer: { role: r.writer.role, pid: r.writer.pid, startedAt: formatTimestamp(r.writer.startedAt) },
        detail: stopDetailOf(r),
        detailRaw: textField(r, 'detail'),
        times,
      };
    }),
    corruptLines: read.corruptLines,
    unknownSchemaLines: read.unknownSchemaLines,
    unknownTypeLines: read.unknownTypeLines,
    tornTail: read.tornTail,
  };
}

// ---------------------------------------------------------------------------------------------
// Writer (ExitLedgerWriter)
// ---------------------------------------------------------------------------------------------

/** Historical evidence is bounded at 128 MiB per family, plus current (2 MiB) and loss (1 MiB). */
const RETAIN_BYTES = 128 * 1024 * 1024;
const LOSS_BYTES = 1024 * 1024;
// Original evidence bounds survive journal compaction. An unknown range stays explicitly
// unknown; the row's `at` (its latest eviction time) remains its upper bound, so compaction
// keeps the newest eviction time there.
function lossScope(rows) {
  const sessions = [...new Set(rows.flatMap(r => r.affectedSessions ?? [r.incarnation?.session]).filter(Boolean))];
  const bounded = sessions.length <= 128 && sessions.join('').length <= 2048;
  const scopeComplete = rows.length > 0 && bounded && rows.every(r => r.reason === 'EVIDENCE_LOST'
    ? r.scopeComplete === true || typeof r.incarnation?.session === 'string' : typeof r.incarnation?.session === 'string');
  const ranges = rows.map(r => r.reason === 'EVIDENCE_LOST'
    ? [knownTimestamp(r.evidenceFrom), knownTimestamp(r.evidenceThrough)] : [knownTimestamp(r.at), knownTimestamp(r.at)]);
  const evidenceRangeComplete = rows.length > 0 && ranges.every(([a, b]) => a !== null && b !== null);
  return { affectedSessions: bounded ? sessions : [], scopeComplete, evidenceRangeComplete,
    ...(evidenceRangeComplete ? { evidenceFrom: formatTimestamp(Math.min(...ranges.map(r => r[0]))),
      evidenceThrough: formatTimestamp(Math.max(...ranges.map(r => r[1]))) } : {}) };
}
/** A JSON object parsed from one retained line, or null when the line is not one. */
function lossRecord(line) {
  try {
    const row = JSON.parse(line);
    return row && typeof row === 'object' && !Array.isArray(row) ? row : null;
  } catch {
    return null;
  }
}
function ledgerFiles(ledgerDir, family) {
  const current = currentPath(ledgerDir, family), stem = path.basename(current, '.jsonl');
  if (!fs.existsSync(ledgerDir)) return [];
  const archives = fs.readdirSync(ledgerDir).filter(name => name.startsWith(stem + '.')
    && /^\d+\.jsonl$/.test(name.slice(stem.length + 1)))
    .sort((a, b) => Number(a.split('.').at(-2)) - Number(b.split('.').at(-2)));
  return [...archives.filter(name => !name.endsWith('.1.jsonl')),
    ...archives.filter(name => name.endsWith('.1.jsonl')), stem + '.lost.jsonl', path.basename(current)]
    .map(name => path.join(ledgerDir, name)).filter(file => fs.existsSync(file));
}
function retainAndRename(current, rotated, bound = RETAIN_BYTES) {
  const dir = path.dirname(current), family = path.basename(current).startsWith('children.') ? 'children' : 'engine';
  const files = ledgerFiles(dir, family);
  if (fs.existsSync(rotated)) {
    const next = Math.max(1, ...files.map(file => Number(path.basename(file).split('.').at(-2))).filter(Number.isFinite)) + 1;
    fs.renameSync(rotated, rotated.replace(/\.1\.jsonl$/, `.${next}.jsonl`));
  }
  fs.renameSync(current, rotated);
  const candidates = ledgerFiles(dir, family).filter(file => !file.endsWith('.lost.jsonl'));
  let size = candidates.reduce((sum, file) => sum + fs.statSync(file).size, 0);
  const lossFile = current.replace(/\.jsonl$/, '.lost.jsonl');
  for (const file of candidates) {
    if (size <= bound || file === rotated) break;
    const bytes = fs.statSync(file).size;
    // Journal lines keep their text and order; one that does not parse is counted and kept
    // verbatim, never dropped: losing a record is worse than keeping a bad line.
    const journal = (fs.existsSync(lossFile) ? fs.readFileSync(lossFile, 'utf8').split('\n') : [])
      .filter(line => line.trim()).map(line => ({ line, row: lossRecord(line) }));
    let losses = journal.filter(entry => entry.row).map(entry => entry.row);
    const unreadable = journal.filter(entry => !entry.row).map(entry => entry.line);
    let corruptLines = unreadable.length;
    const added = [], dropped = [];
    for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      const row = lossRecord(line);
      if (!row) { corruptLines++; dropped.push({}); continue; }
      dropped.push(row);
      if (!Number.isInteger(row.pid) || !/^\d+$/.test(row.creationFileTime ?? '')) continue;
      added.push({ schemaVersion: row.schemaVersion, type: 'ledger-gap', seq: row.seq, at: row.at,
        writer: row.writer, reason: 'EVIDENCE_LOST', pid: row.pid, creationFileTime: row.creationFileTime,
        owner: row.owner, incarnation: row.incarnation, ...lossScope([row]), count: 1, since: row.at, droppedFile: path.basename(file), droppedBytes: bytes });
    }
    added.push({ schemaVersion: 1, type: 'ledger-gap', seq: 1, at: formatTimestamp(Date.now()),
      writer: { role: 'engine', pid: process.pid, startedAt: formatTimestamp(Date.now() - process.uptime() * 1000) },
      reason: 'EVIDENCE_LOST', count: 1, since: formatTimestamp(Date.now()), droppedFile: path.basename(file),
      droppedBytes: bytes, corruptLines, ...lossScope(dropped), detail: 'archive dropped at retention bound' });
    losses = [...losses, ...added];
    let lines = [...journal.map(entry => entry.line), ...added.map(r => JSON.stringify(r))];
    if (Buffer.byteLength(lines.join('\n')) > LOSS_BYTES / 2 && losses.length) {
      const births = losses.flatMap(r => [r.birthFrom ?? r.creationFileTime, r.birthThrough ?? r.creationFileTime])
        .filter(v => /^\d+$/.test(String(v ?? ''))).map(BigInt);
      losses = [{ schemaVersion: 1, type: 'ledger-gap', seq: 1, at: losses.at(-1).at, writer: losses.at(-1).writer,
        reason: 'EVIDENCE_LOST', since: losses.at(-1).at, ...(births.length ? { birthFrom: births.reduce((a,b) => a < b ? a : b).toString(),
          birthThrough: births.reduce((a,b) => a > b ? a : b).toString() } : {}), count: losses.reduce((n,r) => n + (r.count ?? 1), 0),
        ...lossScope(losses), detail: 'loss journal compacted; identity detail and membership unknown' }];
      lines = [...losses.map(r => JSON.stringify(r)), ...unreadable];
    }
    const temp = lossFile + '.pending';
    const fd = fs.openSync(temp, 'w');
    try { fs.writeFileSync(fd, lines.join('\n') + '\n'); fs.fsyncSync(fd); }
    finally { fs.closeSync(fd); }
    fs.renameSync(temp, lossFile); // durable loss record precedes removal
    fs.unlinkSync(file);
    size -= bytes;
  }
}
/** Kept as the public test seam; now retains evidence rather than deleting its predecessor. */
function deleteAndRename(current, rotated) { retainAndRename(current, rotated); }

/** Converts accepted value shapes to JSON-ready values: Dates become wire instants. */
function wire(value) {
  if (value === undefined) return null;
  if (value instanceof Date) return formatTimestamp(value.getTime());
  if (Array.isArray(value)) return value.map(wire);
  if (value !== null && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = wire(v);
    return out;
  }
  if (typeof value === 'bigint') return value.toString();
  return value;
}

/**
 * Whether the file ends without a newline. An unreadable file counts as torn: the extra newline is
 * an empty line a reader skips, while a missing one would merge the record into a torn line.
 */
function endsTorn(file, fsImpl) {
  let fd;
  try {
    fd = fsImpl.openSync(file, 'r');
    const { size } = fsImpl.fstatSync(fd);
    if (size === 0) return false;
    const last = Buffer.alloc(1);
    fsImpl.readSync(fd, last, 0, 1, size - 1);
    return last[0] !== 0x0a;
  } catch (err) {
    return !(err && err.code === 'ENOENT');
  } finally {
    if (fd !== undefined) { try { fsImpl.closeSync(fd); } catch { /* closed */ } }
  }
}

/**
 * The append-only writer of one family for one writer session.
 *
 * @param {object} options
 * @param {string} options.ledgerDir `<dataDir>/runtime/exit-ledger`
 * @param {'children'|'engine'} options.family
 * @param {{role: string, pid: number, startedAt: number|Date}} options.writer
 * @param {boolean} [options.rotates] true only for the family's single rotating writer
 * @param {() => number} [options.clock] epoch ms
 * @param {(current: string, rotated: string) => void} [options.rotation]
 * @param {object} [options.fsImpl] the fs functions used for appends (a seam for failure tests)
 */
function createLedgerWriter({
  ledgerDir,
  family,
  writer,
  rotates = false,
  clock = () => Date.now(),
  retentionBytes = RETAIN_BYTES,
  rotation = (current, rotated) => retainAndRename(current, rotated, retentionBytes),
  sessionRotateBytes = SESSION_ROTATE_BYTES,
  hardCapBytes = HARD_CAP_BYTES,
  fsImpl = fs,
}) {
  if (!ledgerDir) throw new TypeError('ledgerDir is required');
  familyOf(family);
  if (!writer || !WRITER_ROLES.includes(writer.role)) throw new TypeError('writer.role must be a writer role');
  if (!Number.isInteger(writer.pid) || writer.pid <= 0) throw new TypeError('writer.pid must be positive');
  const startedAtMs = writer.startedAt instanceof Date ? writer.startedAt.getTime() : writer.startedAt;
  if (!Number.isFinite(startedAtMs)) throw new TypeError('writer.startedAt must be an instant');
  if (!(sessionRotateBytes > 0) || hardCapBytes < sessionRotateBytes) {
    throw new RangeError('0 < sessionRotateBytes <= hardCapBytes');
  }
  const native = writer.processIdentity ?? (process.platform === 'linux' && writer.pid === process.pid
    ? processIdentity.readLinuxProcess(process.pid)?.processIdentity : null);
  if (native != null && (!processIdentity.linuxIdentity(native) || native.pid !== writer.pid))
    throw new TypeError('writer.processIdentity must name the writer PID');
  const writerBlock = { role: writer.role, pid: writer.pid, startedAt: formatTimestamp(startedAtMs),
    ...(native ? { processIdentity: native } : {}) };
  const current = currentPath(ledgerDir, family);
  const rotated = rotatedPath(ledgerDir, family);
  /** reason -> {count, since}; written in GAP_REASONS order. */
  const pendingGaps = new Map();
  let seq = 0;
  let healthy = true;

  const encode = (type, fields, atMs, recordSeq) => {
    const node = {
      schemaVersion: SCHEMA_VERSION,
      type,
      seq: recordSeq,
      at: formatTimestamp(atMs),
      writer: writerBlock,
    };
    for (const [key, value] of Object.entries(fields)) node[key] = wire(value);
    return Buffer.from(`${JSON.stringify(node)}\n`, 'utf8');
  };

  const noteGap = (reason, nowMs) => {
    const existing = pendingGaps.get(reason);
    if (existing) existing.count += 1;
    else pendingGaps.set(reason, { count: 1, since: formatTimestamp(nowMs) });
  };

  const writeLine = (bytes) => {
    fsImpl.mkdirSync(ledgerDir, { recursive: true });
    const fd = fsImpl.openSync(current, 'a');
    try {
      let offset = 0;
      while (offset < bytes.length) offset += fsImpl.writeSync(fd, bytes, offset, bytes.length - offset);
      fsImpl.fsyncSync(fd);
    } finally {
      fsImpl.closeSync(fd);
    }
  };

  const rotate = (nowMs) => {
    try {
      rotation(current, rotated);
    } catch {
      // Keep appending to the oversized file rather than lose records; say so in the ledger.
      noteGap('ROTATE_FAILED', nowMs);
    }
  };

  const rotateIfOverCap = (recordBytes, nowMs) => {
    let size;
    try {
      size = fsImpl.statSync(current).size;
    } catch {
      return; // missing: nothing to rotate; unreadable: the append itself will fail and say so
    }
    const gapBytes = 256 * pendingGaps.size;
    if (size + gapBytes + recordBytes > hardCapBytes) rotate(nowMs);
  };

  return {
    family,
    ledgerDir,
    writer: writerBlock,

    /**
     * Starts the session after the owner has reconciled what the last one left open: closes a torn
     * tail and, for a rotating writer, rotates a file over the session size. Never throws.
     */
    startSession() {
      if (endsTorn(current, fsImpl)) {
        try { writeLine(Buffer.from('\n')); } catch { healthy = false; }
      }
      if (!rotates) return;
      let size = null;
      try {
        size = fsImpl.statSync(current).size;
      } catch (err) {
        if (!(err && err.code === 'ENOENT')) noteGap('ROTATE_FAILED', clock());
      }
      if (size !== null && size > sessionRotateBytes) rotate(clock());
    },

    /**
     * Appends one record and fsyncs it.
     *
     * @returns {boolean} whether the record is durable; false means it was lost and a gap is pending
     * @throws {TypeError|RangeError} for another family's type, an envelope key, or a record over
     *   MAX_RECORD_BYTES (caller bugs, not I/O conditions)
     */
    append(type, fields = {}) {
      if (!belongsTo(type, family)) throw new TypeError(`${type} is not a ${family} record`);
      for (const key of Object.keys(fields)) {
        if (ENVELOPE.has(key)) throw new TypeError(`reserved field: ${key}`);
      }
      const nowMs = clock();
      // Size and cap are decided on the record as it will be written; seq is assigned after rotation
      // so a gap that rotation itself creates still precedes the record in seq order.
      const probe = encode(type, fields, nowMs, seq + pendingGaps.size + 2);
      if (probe.length > MAX_RECORD_BYTES) {
        throw new RangeError(`${type} record is ${probe.length} bytes; the limit is ${MAX_RECORD_BYTES}`);
      }
      if (rotates) rotateIfOverCap(probe.length, nowMs);

      const lines = [];
      for (const reason of GAP_REASONS) {
        const gap = pendingGaps.get(reason);
        if (!gap) continue;
        seq += 1;
        lines.push({
          gap: reason,
          bytes: encode('ledger-gap', { reason, count: gap.count, since: gap.since }, nowMs, seq),
        });
      }
      seq += 1;
      lines.push({ gap: null, bytes: encode(type, fields, nowMs, seq) });

      // Another writer may have left a torn line since the last append; close it in the same write.
      if (endsTorn(current, fsImpl)) {
        lines[0] = { ...lines[0], bytes: Buffer.concat([Buffer.from('\n'), lines[0].bytes]) };
      }
      for (const line of lines) {
        try {
          writeLine(line.bytes);
        } catch {
          noteGap('WRITE_FAILED', nowMs);
          healthy = false;
          return false;
        }
        if (line.gap) pendingGaps.delete(line.gap);
      }
      healthy = true;
      return true;
    },

    /** Whether the last write succeeded and no gap is waiting to be written. */
    healthy() {
      return healthy;
    },
  };
}

module.exports = {
  SCHEMA_VERSION,
  MAX_RECORD_BYTES,
  MAX_READ_LINE_BYTES,
  SESSION_ROTATE_BYTES,
  HARD_CAP_BYTES,
  JS_CHILD_STOP,
  JS_ENGINE_STOP,
  EARLIEST_KNOWN_MS,
  BIRTH_TICKS,
  sameBirth,
  FAMILIES,
  RECORD_TYPES,
  WRITER_ROLES,
  GAP_REASONS,
  TIME_FIELDS,
  ledgerDirectory,
  currentPath,
  rotatedPath,
  knownTimestamp,
  formatTimestamp,
  readLedger,
  createIncrementalReader,
  summarizeLedger,
  stopDetailOf,
  textField,
  createLedgerWriter,
  deleteAndRename,
  ledgerFiles,
  retainAndRename,
  RETAIN_BYTES,
};
