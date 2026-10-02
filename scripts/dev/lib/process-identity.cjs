#!/usr/bin/env node
/**
 * Tempdoc 861 W2 [A2] — process identity that survives pid reuse.
 *
 * Every kill path in 861 must call `verifyProcessIdentity` immediately before acting. A record
 * routinely outlives its process by hours (861 §2), and the reaper's whole job is killing by pid,
 * so "the pid is alive" is not evidence that the pid is still OURS.
 *
 * Three rules this module exists to enforce, none of which is optional:
 *
 *  1. **The conjunction is mandatory** — `pid` AND `creationTime` AND `fingerprint`, never any
 *     subset. The fingerprint alone is a *substring* match on a command line containing e.g.
 *     `vite`; it is safe ONLY as the third term of a conjunction whose second term is an exact
 *     creation-time equality, and dangerously permissive on its own (861 §6.2).
 *
 *  2. **Exact equality on the creation time, no tolerance window.** A tolerance is a second
 *     identity bug waiting: pid reuse on a busy Windows host can land inside any window loose
 *     enough to absorb clock jitter (861 §6.2).
 *
 *  3. **Evidence-unavailable is REFUSE, never "proceed because nothing contradicted us."** REFUSE
 *     is a THIRD verdict value, not `false`. The repo precedent is `classifyActivity`, which
 *     returns `{ known: false, generalStale: false }` so an absent signal cannot masquerade as a
 *     permissive one (`scripts/dev/lib/ownership-verdict.cjs:82-92`). It needs enforcing in code
 *     here because the neighbouring helper fails silently to empty by design —
 *     `getProcessTable` returns `[]` on ANY failure (`scripts/dev/remove-worktree.cjs:125-131`) —
 *     and a reaper reading that `[]` as "no conflicting evidence" would re-ship that defect inside
 *     a component whose job is killing things.
 *
 * **Why the creation time is carried as a decimal STRING, not a JSON number.** Measured on this
 * host, 2026-08-25: `Win32_Process.CreationDate.ToFileTimeUtc()` yields 18-digit values such as
 * `134320479841300350`. `Number.MAX_SAFE_INTEGER` is 16 digits (9007199254740991), so a FILETIME
 * cannot be represented exactly as a JSON number: `Number('134320479841300350') ===
 * Number('134320479841300351')` is `true` in Node. Two creation times 100 ns apart therefore
 * COLLAPSE under a double, which is precisely the "exact equality" rule 2 forbids weakening. So
 * PowerShell emits `.ToFileTimeUtc().ToString()`, and `normalizeCreationTime` canonicalizes via
 * `BigInt`. A creation time arriving as a JSON *number* is unreadable evidence (REFUSE) — ALL of
 * them, not merely the unsafe ones: a FILETIME small enough to be a safe integer would place the
 * process's creation before 1629, so the "safe integer" branch could only ever admit a value that
 * is not a real creation time in the first place.
 *
 * **Rule 4, added by review: the evidence must be FRESH.** The three conjuncts all hold against a
 * STALE process-table row: a row captured before the process exited still names the same pid,
 * creation time, and command line, so a caller that hoists one `readProcessTable` out of a sweep
 * loop can verify against evidence describing a process that has since died and had its pid
 * recycled — and no mutation to the conjunction would catch it. So a table carries a `readAt`
 * stamp, `verifyProcessIdentity` REFUSES past `maxTableAgeMs`, and a bare unstamped array REFUSES
 * outright unless the caller names the waiver. Same standard as `isVerifiedMatch`: the unsafe
 * spelling is the one that must be unwritable.
 *
 * Nothing in this module kills, signals, or writes anything. It answers one question.
 */
'use strict';

const { spawnSync, execFile } = require('node:child_process');

/**
 * The ONE process-table projection. `remove-worktree.cjs` consumes this same constant so the two
 * cannot drift: rev 1 of 861 assumed the creation time was "already available" from that scan, but
 * its `Select-Object` projected `CreationDate` away (`remove-worktree.cjs:119-120` before this
 * change) — a single shared constant is what stops that assumption from being made twice.
 *
 * `CreationDate` is a `[DateTime]` on the CIM cmdlets, so `.ToFileTimeUtc()` normalizes it to a
 * locale-independent 64-bit integer instead of a CIM datetime string whose format depends on the
 * host's culture. Kernel-owned rows (`System Idle Process`) can carry a null `CreationDate`; the
 * guard emits `$null` for those rather than throwing, and the JS side reads a null as
 * evidence-unavailable.
 *
 * 861 production orientation sweep (2026-08-25): every identity verification on one host degraded
 * to REFUSE with `process-table output is not JSON: Bad control character in string literal in
 * JSON at position 118004`. Reproduced (not merely hypothesized) with a disposable child process
 * whose argv embedded U+2022 ('•'): Windows PowerShell 5.1's `[Console]::OutputEncoding` when
 * non-interactive AND redirected is the OEM codepage (IBM437 / CP437 on this host), NOT UTF-8 —
 * confirmed via `[Console]::OutputEncoding.WebName` under `-NoProfile -NonInteractive` with stdout
 * piped, same as this module's `spawnSync` call. `ConvertTo-Json` does its job correctly: U+2022 is
 * far above the C0 range, so it is emitted unescaped, which is valid JSON. The corruption happens
 * ONE STEP LATER, purely in the OS text-encoding layer: CP437 is the historical IBM-PC OEM codepage,
 * whose low byte values 0x01-0x1F double as GLYPHS for box-drawing/suit/arrow symbols (e.g. U+2022
 * '•' -> byte 0x07, U+263A '☺' -> byte 0x01, U+2192 '→' -> byte 0x1A) — a design predating the
 * existence of Unicode, when those bytes only ever meant "draw this glyph" on PC display hardware.
 * When PowerShell's console host writes the (valid) JSON string through that encoding, the
 * printable Unicode symbol becomes a single raw CP437 byte in the 0x01-0x1F range. That byte is
 * ALSO a valid one-byte UTF-8 code point (ASCII identity range), so `spawnSync({encoding:'utf8'})`
 * decodes it losslessly back into a genuine C0 control character sitting unescaped inside what was,
 * one hop earlier, valid JSON — reproducing the exact "Bad control character" message and position
 * shape. A brute-force scan of the CP437-encode -> UTF-8-decode round trip across every BMP code
 * point (`scan-cp437.ps1`, ad hoc) found 34 codepoints with this property, all common symbols a
 * real-world process could plausibly carry in its command line (bullets, section/pilcrow marks,
 * arrows, card suits, music notes). The root-cause fix is therefore the ENCODING step, not a
 * missing escape: forcing the console's actual output encoding to match what the Node side decodes
 * with (`utf8`) eliminates the whole codepage-glyph-collision class, verified by re-running the same
 * repro after the encoding line was added (no corruption, U+2022/U+263A round-tripped intact).
 *
 * The `-replace` calculated properties below are a SEPARATE, complementary layer: they guard
 * against a genuine raw control byte actually present in `CommandLine`/`Name` (e.g. a corrupted WMI
 * cross-process memory read for a protected process — a documented, independent WMI failure mode,
 * distinct from the codepage bug above) reaching `ConvertTo-Json` at all. Tab/LF/CR are excluded
 * because `ConvertTo-Json` already escapes them correctly (`\t`/`\n`/`\r`) and they are legitimate,
 * harmless content; every other C0 control is stripped. Neither layer alone is sufficient: the
 * encoding fix does not help if the source string already contains a genuine raw control byte
 * (CP437 round-trips ASCII control bytes as themselves), and the sanitizer does not help against a
 * printable symbol being mis-encoded downstream — hence both.
 */
const PROCESS_TABLE_PS_COMMAND =
  '[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); ' +
  'Get-CimInstance Win32_Process | Select-Object ' +
  "@{Name='ProcessId';Expression={$_.ProcessId}}," +
  "@{Name='ParentProcessId';Expression={$_.ParentProcessId}}," +
  "@{Name='Name';Expression={ if ($_.Name) { $_.Name -replace '[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]','' } else { $_.Name } }}," +
  "@{Name='CommandLine';Expression={ if ($_.CommandLine) { $_.CommandLine -replace '[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]','' } else { $_.CommandLine } }}," +
  "@{Name='CreationFileTimeUtc';Expression={ if ($_.CreationDate) { $_.CreationDate.ToFileTimeUtc().ToString() } else { $null } }}" +
  ' | ConvertTo-Json -Compress -Depth 2';

/**
 * Best-effort diagnostic for a `JSON.parse` failure on process-table output, so REFUSE carries
 * enough for the next operator to act instead of grepping logs (`verify-dont-guess`). Pulls the
 * byte position V8 reports out of the error message and prints a short window around it, flagging
 * any C0 control byte that window still contains (post-sanitization, that indicates the encoding
 * layer, not a missing string sanitize — see `PROCESS_TABLE_PS_COMMAND`'s comment).
 *
 * Deliberately best-effort: an error message shape V8 changes, or non-string `text`, degrades to
 * the plain message rather than throwing from inside a diagnostic.
 */
function describeJsonParseFailure(text, err) {
  const message = String((err && err.message) || err || 'unknown JSON.parse error');
  if (typeof text !== 'string' || text.length === 0) return message;
  const match = /position (\d+)/.exec(message);
  if (!match) return message;
  const pos = Number(match[1]);
  if (!Number.isFinite(pos)) return message;
  const start = Math.max(0, pos - 40);
  const end = Math.min(text.length, pos + 40);
  const window = text.slice(start, end);
  const controlBytes = [...window]
    .map((ch) => ch.codePointAt(0))
    .filter((cp) => cp !== undefined && cp < 0x20 && cp !== 0x09 && cp !== 0x0a && cp !== 0x0d);
  const controlNote = controlBytes.length
    ? `; control byte(s) in window: ${[...new Set(controlBytes)].map((cp) => `0x${cp.toString(16).padStart(2, '0')}`).join(',')}`
    : '';
  return `${message} — near offset ${pos}: ${JSON.stringify(window)}${controlNote}`;
}

/**
 * The verdict vocabulary. THREE values — a boolean cannot express "I have no evidence", which is
 * the whole point (861 [A2]).
 *
 *   `match`    — pid AND creation time AND fingerprint all agree. The only value that licenses a
 *                kill.
 *   `mismatch` — the evidence was READ and it says this is not our process (recycled pid, dead
 *                pid, different command line). A negative answer, not an absent one.
 *   `refuse`   — the evidence was absent, unparseable, or unavailable. NOT a negative answer, and
 *                emphatically not a positive one: callers report and stop.
 */
const IDENTITY = Object.freeze({
  MATCH: 'match',
  MISMATCH: 'mismatch',
  REFUSE: 'refuse',
});

/**
 * Canonicalize an OS process creation time to a decimal string, or `null` when the evidence is
 * absent or unreadable. `null` means REFUSE at every call site — never "zero", never "old".
 *
 * @param {unknown} value
 * @returns {string|null}
 */
function normalizeCreationTime(value) {
  if (value === null || value === undefined) return null;
  // EVERY JSON number is refused, not merely the unsafe ones. A real FILETIME never fits a double,
  // and one small enough to fit would date the process before 1629 — so the "safe integer" branch
  // could only ever admit a value that is not a creation time at all. Admitting it would be
  // permissiveness with no reachable legitimate input.
  if (typeof value === 'number') return null;
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!/^[0-9]{1,20}$/.test(trimmed)) return null;
  let big;
  try {
    big = BigInt(trimmed);
  } catch {
    return null;
  }
  if (big <= 0n) return null;
  return big.toString();
}

/** How old a process-table snapshot may be and still support a kill decision (rule 4). */
const DEFAULT_MAX_TABLE_AGE_MS = 2000;

const EMPTY_TABLE_REASON =
  'empty process table is NO evidence, not exculpatory evidence (getProcessTable fails silently to [] by design — remove-worktree.cjs:125-131)';

/**
 * Read the local process table as a TRI-STATE: `{ ok: true, table, readAt }` or
 * `{ ok: false, reason }`.
 *
 * Deliberately NOT `getProcessTable`'s `[]`-on-failure contract. That contract is right for a
 * best-effort holder *report* (it degrades to "no holder found" instead of throwing mid-teardown)
 * and wrong for an identity check, where `[]` would read as "nothing contradicts you".
 *
 * `readAt` is part of the result, not an optional extra: it is the only thing that lets a consumer
 * distinguish a snapshot taken moments ago from one hoisted out of a loop minutes earlier, and the
 * three identity conjuncts cannot make that distinction themselves (rule 4).
 *
 * `exec`, `platform`, and `now` are injectable so every branch is unit-testable without spawning
 * PowerShell.
 */
function readProcessTable({ platform = process.platform, exec = spawnSync, now = Date.now } = {}) {
  if (platform !== 'win32') {
    return { ok: false, reason: `process-table enumeration is implemented for win32 only (platform=${platform})` };
  }
  let res;
  try {
    res = exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', PROCESS_TABLE_PS_COMMAND], {
      encoding: 'utf8',
      maxBuffer: 32 * 1024 * 1024,
      timeout: 5000,
      windowsHide: true,
    });
  } catch (err) {
    return { ok: false, reason: `process-table query threw: ${String(err?.message || err).slice(0, 200)}` };
  }
  if (!res || res.status !== 0) {
    return { ok: false, reason: `process-table query exited ${res ? res.status : 'with no result'}` };
  }
  if (!res.stdout) return { ok: false, reason: 'process-table query produced no output' };
  let parsed;
  try {
    parsed = JSON.parse(res.stdout);
  } catch (err) {
    return { ok: false, reason: `process-table output is not JSON: ${describeJsonParseFailure(res.stdout, err).slice(0, 400)}` };
  }
  const table = Array.isArray(parsed) ? parsed : [parsed];
  if (table.length === 0) {
    return { ok: false, reason: 'process-table enumeration returned no rows; a running host always has processes, so this is a failed query, not an empty machine' };
  }
  return { ok: true, table, readAt: now() };
}

/**
 * Accept a `readProcessTable` result, a raw array, or nothing at all, and collapse it to the
 * tri-state — with the empty array, the STALE snapshot, and the unstamped array all landing on the
 * REFUSE side, which are the branches that matter.
 *
 * `acceptUnstampedTable` is the deliberately verbose waiver for a caller that produced rows some
 * other way and has bounded their age itself. It has to be named, because the default has to be
 * the safe one: a bare array is the shape a hoisted-out-of-the-loop snapshot arrives in.
 */
function coerceProcessTable(table, { now = Date.now(), maxTableAgeMs = DEFAULT_MAX_TABLE_AGE_MS, acceptUnstampedTable = false } = {}) {
  if (table === null || table === undefined) {
    return { ok: false, reason: 'no process table supplied' };
  }
  if (Array.isArray(table)) {
    if (table.length === 0) return { ok: false, reason: EMPTY_TABLE_REASON };
    if (!acceptUnstampedTable) {
      return {
        ok: false,
        reason: 'process table is a bare array with no readAt stamp, so its age cannot be bounded; pass a readProcessTable() result, or acceptUnstampedTable:true if the caller has bounded its age itself',
      };
    }
    return { ok: true, table };
  }
  if (typeof table === 'object' && typeof table.ok === 'boolean') {
    if (!table.ok) return { ok: false, reason: table.reason || 'process table unavailable' };
    const rows = table.table;
    if (!Array.isArray(rows)) return { ok: false, reason: 'process table result carries no rows array' };
    if (rows.length === 0) return { ok: false, reason: EMPTY_TABLE_REASON };
    const readAt = table.readAt;
    if (!Number.isFinite(readAt)) {
      if (!acceptUnstampedTable) {
        return { ok: false, reason: `process table carries no usable readAt stamp (${JSON.stringify(readAt)}), so its age cannot be bounded` };
      }
      return { ok: true, table: rows };
    }
    const ageMs = now - readAt;
    if (ageMs > maxTableAgeMs) {
      // The branch the three conjuncts cannot see: a stale row still matches pid, creation time,
      // and fingerprint while the process it describes has exited and had its pid recycled.
      return { ok: false, reason: `process table was read ${ageMs}ms ago, beyond the ${maxTableAgeMs}ms freshness bound; a stale row matches all three conjuncts even after the pid has been recycled` };
    }
    if (ageMs < -maxTableAgeMs) {
      return { ok: false, reason: `process table readAt is ${-ageMs}ms in the future; the stamp cannot be trusted to bound its age` };
    }
    return { ok: true, table: rows, ageMs };
  }
  return { ok: false, reason: `unrecognized process-table shape (${typeof table})` };
}

function verdict(kind, reason, extra = {}) {
  return {
    verdict: kind,
    reason,
    matched: { pid: null, creationTime: null, fingerprint: null, ...(extra.matched || {}) },
    ...(extra.pid !== undefined ? { pid: extra.pid } : {}),
  };
}

/**
 * The single function every kill path calls immediately before acting (861 §6.3).
 *
 * @param {object} args
 * @param {object} args.record - a record carrying `pid`, `creationFileTimeUtc`, `cmdlineFingerprint`.
 * @param {object|Array|null} args.table - a `readProcessTable` result, a raw row array, or nothing.
 * @param {number} [args.maxTableAgeMs] - freshness bound on the snapshot (rule 4).
 * @param {boolean} [args.acceptUnstampedTable] - the named waiver for an unstamped table.
 * @param {number} [args.now]
 * @returns {{verdict: 'match'|'mismatch'|'refuse', reason: string, matched: object, pid?: number}}
 */
function verifyProcessIdentity({ record, table, maxTableAgeMs = DEFAULT_MAX_TABLE_AGE_MS, acceptUnstampedTable = false, now = Date.now() } = {}) {
  const pid = record?.pid;
  if (!Number.isInteger(pid) || pid <= 0) {
    return verdict(IDENTITY.REFUSE, `record declares no usable pid (${JSON.stringify(record?.pid)})`);
  }
  const recordedTime = normalizeCreationTime(record?.creationFileTimeUtc);
  if (recordedTime === null) {
    return verdict(
      IDENTITY.REFUSE,
      `record's creationFileTimeUtc is absent or unreadable (${JSON.stringify(record?.creationFileTimeUtc)}); identity cannot be established, so no kill path may proceed`,
      { pid },
    );
  }
  const fingerprint = typeof record?.cmdlineFingerprint === 'string' ? record.cmdlineFingerprint.trim() : '';
  if (!fingerprint) {
    return verdict(IDENTITY.REFUSE, 'record declares no cmdlineFingerprint; the third conjunct cannot be evaluated', { pid });
  }

  const resolved = coerceProcessTable(table, { now, maxTableAgeMs, acceptUnstampedTable });
  if (!resolved.ok) {
    return verdict(IDENTITY.REFUSE, `process table unusable: ${resolved.reason}`, { pid });
  }

  const row = resolved.table.find((r) => Number(r?.ProcessId) === pid);
  if (!row) {
    // The table WAS read, and this pid is not in it. That is a read negative, not an absent one:
    // the process is gone, so nothing can be killed and nothing needs refusing.
    return verdict(IDENTITY.MISMATCH, `pid ${pid} is not present in the process table (the process is gone)`, {
      pid,
      matched: { pid: false },
    });
  }

  const liveTime = normalizeCreationTime(row.CreationFileTimeUtc);
  if (liveTime === null) {
    return verdict(
      IDENTITY.REFUSE,
      `pid ${pid} is present but its creation time is absent or unreadable (${JSON.stringify(row.CreationFileTimeUtc)}); pid reuse cannot be ruled out`,
      { pid, matched: { pid: true } },
    );
  }
  if (liveTime !== recordedTime) {
    // The recycled-pid branch. Exact equality, no tolerance window (rule 2).
    return verdict(
      IDENTITY.MISMATCH,
      `pid ${pid} was recycled: the live process was created at ${liveTime}, the record names ${recordedTime}`,
      { pid, matched: { pid: true, creationTime: false } },
    );
  }

  const cmdline = typeof row.CommandLine === 'string' ? row.CommandLine : null;
  if (cmdline === null) {
    return verdict(
      IDENTITY.REFUSE,
      `pid ${pid} matches on creation time but its CommandLine is unavailable, so the fingerprint conjunct cannot be evaluated`,
      { pid, matched: { pid: true, creationTime: true } },
    );
  }
  if (!cmdline.includes(fingerprint)) {
    return verdict(
      IDENTITY.MISMATCH,
      `pid ${pid} matches on creation time but its command line does not contain the recorded fingerprint ${JSON.stringify(fingerprint)}`,
      { pid, matched: { pid: true, creationTime: true, fingerprint: false } },
    );
  }

  return verdict(IDENTITY.MATCH, `pid ${pid} matches on pid, creation time, and command-line fingerprint`, {
    pid,
    matched: { pid: true, creationTime: true, fingerprint: true },
  });
}

/**
 * The ONLY safe way to read the verdict at a kill site. Written as an explicit `=== MATCH` so a
 * caller cannot accidentally spell it `!== MISMATCH` and turn a REFUSE into a licence.
 */
function isVerifiedMatch(result) {
  return result?.verdict === IDENTITY.MATCH;
}

/** Bounded, non-blocking capture for supervisors that must keep observing child exits. */
async function readProcessTableAsync({ platform = process.platform, exec = execFile, now = Date.now } = {}) {
  if (platform !== 'win32') return readProcessTable({ platform });
  const result = await new Promise(resolve => {
    exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', PROCESS_TABLE_PS_COMMAND],
      { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, timeout: 5000, windowsHide: true },
      (error, stdout) => resolve({ status: error ? 1 : 0, stdout }));
  });
  return readProcessTable({ platform, exec: () => result, now });
}

module.exports = {
  readProcessTableAsync,
  PROCESS_TABLE_PS_COMMAND,
  IDENTITY,
  DEFAULT_MAX_TABLE_AGE_MS,
  normalizeCreationTime,
  describeJsonParseFailure,
  readProcessTable,
  coerceProcessTable,
  verifyProcessIdentity,
  isVerifiedMatch,
};
