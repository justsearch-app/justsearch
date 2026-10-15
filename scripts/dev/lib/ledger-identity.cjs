/* One identity value per recorded process, and the one rule every stop consumer compares two with
 * (the b102 round-8 decision).
 *
 * An identity is everything a record says about which process it is: the PID; the birth (a Windows
 * FILETIME) with the tick it was measured at and where it was read; and, where recorded, the childId,
 * the job witness, the owner (an identity of its own: the owning Engine's PID, birth and instance) and
 * the Engine incarnation. A ledger start's identity is built once, when its record is read, and is
 * never overwritten: later metadata that disagrees with it is a conflict on it (`conflicts`), which the
 * census reports as a finding.
 *
 * `compareIdentity(a, b, rule, ctx)` is the one comparison. Every consumer passes the whole value it
 * holds (a ledger start, a ledger record, a census target, an OS row, a tracked record of the runner,
 * a port owner's row), never a projection of it:
 *   'same'        both name one process: both carry a PID and a birth, the PIDs are equal, the births
 *                 agree at the coarser of their two measured ticks, and every other identity field both
 *                 carry (childId, witnessId, owner instance, owner, incarnation) agrees.
 *   'proves'      ledger record `a` names start `b` (frozen identity) well enough to prove its end. A
 *                 key-only record (a childId with no PID, birth or incarnation; an Engine incarnation
 *                 with no birth, whose PID, when the dev runner's engine-owner-died names one, must
 *                 agree) proves only the one start in the run with that key
 *                 (`ctx.keyStarts === 1`), never a start with conflicting metadata, never when another
 *                 record of the key contradicts the start (`ctx.contested`); for a child, only
 *                 when the owner the record names (owner, previousOwner) or else its writer is the
 *                 start's owner instance; for an Engine, only when its writer is a supervisor (shell,
 *                 dev runner, dev CLI), that Engine itself (its PID, started its millisecond) or a
 *                 writer that recorded the start (`ctx.startWriters`: role, PID and start instant).
 *                 Any other record must carry and agree on every field the start carries.
 *   'contradicts' some field both carry disagrees (a record about another process of the same key).
 *   'exact'       one start: the same fields, each equal, the births to the tick.
 *   'native'      one process by PID and birth alone, for pairing records whose other fields describe
 *                 different relations (a receipt's creator, a witness's Engine); never a proof.
 * The precision and ordering helpers live here too, so no consumer compares a PID or a birth itself. */
'use strict';
const { knownTimestamp } = require('./exit-ledger.cjs');

const EPOCH_FILETIME = 116444736000000000n;
/** The ticks a birth is measured at (exit-ledger.cjs BIRTH_TICKS): GetProcessTimes, CIM, Java startInstant. */
const TICK = Object.freeze({ EXACT: 1n, MICROSECOND: 10n, MILLISECOND: 10000n });
const SOURCE_TICK = Object.freeze({ 'B11.startInstant': TICK.MILLISECOND, CIM: TICK.MICROSECOND, native: TICK.EXACT });
const IDENTITY = Symbol('ledger identity');

const isFileTime = (v) => typeof v === 'string' && /^[1-9][0-9]{0,19}$/.test(v);
const tickOf = (v) => (/^[1-9]\d{0,9}$/.test(String(v ?? '')) ? BigInt(v) : null);
const validPid = (v) => {
  if (v == null || v === '') return null;
  const n = Number(v);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
};
const birthField = (x) => {
  const v = x?.creationFileTimeUtc ?? x?.creationFileTime ?? x?.CreationFileTimeUtc ?? null;
  return v == null ? null : String(v);
};
const incarnationOf = (inc) => (inc && typeof inc === 'object'
  ? Object.freeze({ session: inc.session ?? null, n: inc.n ?? null }) : null);

// The value read from a plain object, by the tick a birth that names none is read at, with the fields
// it was read from: an object whose identity fields change since (a test or a caller updating a row in
// place) is read again, never answered with the value of what it was.
const derived = new WeakMap();
const ownerField = (x) => x.owner ?? x.previousOwner;
const sourceOf = (x) => [x.pid, x.ProcessId, x.creationFileTimeUtc, x.creationFileTime, x.CreationFileTimeUtc, x.birthTick,
  x.creationTimeSource, x.childId, x.witnessId, x.instanceId, x.owner, x.previousOwner, x.incarnation, x.incarnation?.session,
  x.incarnation?.n];
const unchanged = (hit, x) => {
  const now = sourceOf(x);
  if (!hit.source.every((v, i) => v === now[i])) return false;
  // The owner is an object of its own: its value is current when it is the value read from it now.
  const owner = ownerField(x);
  return !(owner && typeof owner === 'object') || identityOf(owner, { defaultTick: TICK.EXACT }) === hit.value.owner;
};
/**
 * The identity value of `x`. A value built by identityOf is returned as it is. Any other object
 * (a ledger line, a target, an OS row, a tracked record) is read for every identity field it
 * carries; its birth tick is its `birthTick`, else the tick of its `creationTimeSource`, else
 * `defaultTick` (the microsecond, CIM's, for a birth whose clock is not named).
 */
function identityOf(x, { tick = null, source = null, kind = null, defaultTick = TICK.MICROSECOND, owner, incarnation,
  childId, witnessId } = {}) {
  if (x?.[IDENTITY]) return x;
  if (x?.identity?.[IDENTITY]) return x.identity;
  if (!x || typeof x !== 'object') return null;
  const plain = tick === null && source === null && kind === null && owner === undefined && incarnation === undefined
    && childId === undefined && witnessId === undefined;
  if (plain) {
    const hit = derived.get(x)?.get(String(defaultTick));
    if (hit && unchanged(hit, x)) return hit.value;
  }
  const value = build(x, { tick, source, kind, defaultTick, owner, incarnation, childId, witnessId });  if (plain) {
    if (!derived.has(x)) derived.set(x, new Map());
    derived.get(x).set(String(defaultTick), { value, source: sourceOf(x) });
  }
  return value;
}

function build(x, { tick, source, kind, defaultTick, owner, incarnation, childId, witnessId }) {
  const birth = birthField(x);
  // A reconciliation names the owner it found dead as `previousOwner`.
  const ownerValue = owner !== undefined ? owner : ownerField(x);
  const value = {
    [IDENTITY]: true,
    pid: validPid(x.pid ?? x.ProcessId),
    birth: isFileTime(birth) ? birth : null,
    rawBirth: birth,
    // A birth read natively (GetProcessTimes) is exact whatever tick a record declares beside it: two
    // native FILETIMEs are compared to the tick, never at a coarser declaration (round 9).
    tick: String(tick ?? (x.creationTimeSource === 'native' ? TICK.EXACT : null) ?? tickOf(x.birthTick)
      ?? SOURCE_TICK[x.creationTimeSource] ?? defaultTick),
    source: source ?? x.creationTimeSource ?? null,
    kind: kind ?? null,
    childId: (childId !== undefined ? childId : x.childId) ?? null,
    witnessId: (witnessId !== undefined ? witnessId : x.witnessId) ?? null,
    instanceId: x.instanceId ?? null,
    owner: ownerValue && typeof ownerValue === 'object' ? identityOf(ownerValue, { defaultTick: TICK.EXACT }) : null,
    incarnation: incarnationOf(incarnation !== undefined ? incarnation : x.incarnation),
    conflicts: [],
  };
  // Carried on the records it is read from without being written out with them.
  Object.defineProperty(value, 'toJSON', { value: () => undefined });
  // The value is frozen; its conflicts list only grows (later metadata never replaces a field).
  Object.freeze(value);
  return value;
}

/** A start's identity, read once from its ledger record (births in the ledger are native unless named). */
function startIdentity(fields) {
  return identityOf(fields, { tick: fields.tick ?? TICK.EXACT, source: fields.source ?? 'ledger', kind: fields.kind ?? null,
    owner: fields.owner ?? null, incarnation: fields.incarnation ?? null, childId: fields.childId ?? null,
    witnessId: fields.witnessId ?? null });
}

/** Records later metadata that disagrees with a start's identity, without changing the identity. */
function noteConflict(identity, conflict) {
  const id = identityOf(identity);
  if (id && !id.conflicts.some(c => c.field === conflict.field && c.value === conflict.value)) id.conflicts.push(conflict);
  return id;
}

const coarser = (a, b) => { const x = BigInt(a.tick), y = BigInt(b.tick); return x > y ? x : y; };
const birthsAgree = (a, b, tick = coarser(a, b)) => a.birth !== null && b.birth !== null
  && BigInt(a.birth) / tick === BigInt(b.birth) / tick;
const sameIncarnation = (a, b) => a.session === b.session && a.n === b.n;
const both = (a, b, f) => a[f] != null && b[f] != null;

/** Two values name one process (rule 'same'). */
function same(A, B) {
  if (!A || !B || A.pid === null || B.pid === null || A.pid !== B.pid || !birthsAgree(A, B)) return false;
  for (const f of ['childId', 'witnessId', 'instanceId']) if (both(A, B, f) && A[f] !== B[f]) return false;
  if (both(A, B, 'owner') && !same(A.owner, B.owner)) return false;
  if (both(A, B, 'incarnation') && !sameIncarnation(A.incarnation, B.incarnation)) return false;
  return true;
}

/** Some identity field both carry disagrees (rule 'contradicts'). */
function contradicts(A, B) {
  if (!A || !B) return false;
  if (both(A, B, 'pid') && A.pid !== B.pid) return true;
  if (A.rawBirth != null && B.birth !== null && !birthsAgree(A, B)) return true;
  for (const f of ['childId', 'witnessId', 'instanceId']) if (both(A, B, f) && A[f] !== B[f]) return true;
  if (both(A, B, 'owner') && contradicts(A.owner, B.owner)) return true;
  if (both(A, B, 'incarnation') && !sameIncarnation(A.incarnation, B.incarnation)) return true;
  return false;
}

/**
 * The owner a record names agrees with a start's owner: every field the start's owner carries (PID,
 * birth, instance) is on the named owner and the same. A field left out is never a wildcard.
 */
function ownerCarries(named, owner) {
  if (!owner) return true;
  if (!named) return false;
  if (owner.pid !== null && named.pid !== owner.pid) return false;
  if (owner.rawBirth != null && !birthsAgree(named, owner)) return false;
  if (owner.instanceId != null && named.instanceId !== owner.instanceId) return false;
  return true;
}

/**
 * The writer of a record is the owner instance `owner` (an Engine): an Engine writer with its PID, and
 * its birth to the millisecond (`startedAt`). `unknownBirth` admits a writer whose start time is not
 * readable (a receipt's writer check, which an owner reference on the record binds as well); a proof
 * from the writer alone never does.
 */
function writerIsOwner(writer, owner, { unknownBirth = false } = {}) {
  const O = identityOf(owner, { defaultTick: TICK.EXACT });
  if (!writer || writer.role !== 'engine' || !O || O.pid === null) return false;
  if (validPid(writer.pid) !== O.pid) return false;
  const startedMs = Number.isFinite(writer.startedAt) ? writer.startedAt : knownTimestamp(writer.startedAt);
  if (startedMs == null) return unknownBirth;
  return O.birth !== null && birthMs(O) === startedMs;
}

/** The writer roles that supervise an Engine and write its records (exit-ledger.cjs WRITER_ROLES but 'engine'). */
const SUPERVISOR_ROLES = new Set(['shell', 'dev-runner', 'dev-cli']);

/** A writer's start instant in milliseconds (the ledger reader gives it as a number), or null. */
const writerStartedMs = (w) => (Number.isFinite(w?.startedAt) ? w.startedAt : knownTimestamp(w?.startedAt) ?? null);
/** The same writer: one role, one PID, one start instant, all recorded. */
const sameWriter = (a, b) => Boolean(a && b) && typeof a.role === 'string' && a.role === b.role
  && validPid(a.pid) !== null && validPid(a.pid) === validPid(b.pid)
  && writerStartedMs(a) !== null && writerStartedMs(a) === writerStartedMs(b);

/**
 * Ledger record line `line` (its writer `writer`) proves start `S` (rule 'proves'). `startWriters`:
 * the writers that recorded the start (an Engine start's).
 */
function proves(line, S, { keyStarts = 1, contested = false, writer = null, family = null, startWriters = [] } = {}) {
  if (!line || !S) return false;
  const R = identityOf(line, { defaultTick: TICK.EXACT, owner: line.owner ?? line.previousOwner ?? null });
  if (S.conflicts.length) return false;
  const engine = (family ?? S.kind) === 'engine';
  // Without its birth an Engine record is about its incarnation (the dev runner's engine-owner-died
  // names the PID beside it, which must then agree); a child record without PID, birth or incarnation
  // is about its childId.
  const keyOnly = engine ? line.creationFileTime == null
    : line.pid == null && line.creationFileTime == null && line.incarnation == null;
  if (keyOnly) {
    if (keyStarts !== 1 || contested || contradicts(R, S)) return false;
    // An Engine's incarnation-only record is the word of its supervisor (the shell, the dev runner, a
    // dev CLI stop), of that Engine itself (an Engine writer with the start's own PID, started the
    // start's millisecond), or of the writer that recorded the start. Any other Engine's word about it
    // proves nothing (round 9).
    if (engine) {
      return SUPERVISOR_ROLES.has(writer?.role) || writerIsOwner(writer, S) || startWriters.some(w => sameWriter(writer, w));
    }
    if (line.owner != null || line.previousOwner != null) return ownerCarries(R.owner, S.owner) && S.owner !== null;
    return writerIsOwner(writer, S.owner);
  }
  if (S.pid === null || R.pid !== S.pid || S.birth === null || !birthsAgree(R, S)) return false;
  for (const f of ['childId', 'witnessId']) if (S[f] != null && R[f] !== S[f]) return false;
  if (S.owner && !ownerCarries(R.owner, S.owner)) return false;
  if (S.incarnation && !(R.incarnation && sameIncarnation(R.incarnation, S.incarnation))) return false;
  return true;
}

/**
 * One process, whatever else either says about it (rule 'native'): the PIDs equal and the births agree
 * at the coarser tick. Only for pairing two records whose other fields describe different relations
 * (a helper receipt names its creator as `owner`, its job witness the Engine), or for one start read
 * twice; never for proving or suppressing an identity.
 */
function native(A, B) {
  return Boolean(A && B) && A.pid !== null && A.pid === B.pid && birthsAgree(A, B);
}

/** One start: the same fields, each equal, births to the tick (rule 'exact'). */
function exact(A, B) {
  if (!A || !B || A.pid !== B.pid || A.rawBirth !== B.rawBirth) return false;
  for (const f of ['childId', 'witnessId', 'instanceId']) if ((A[f] ?? null) !== (B[f] ?? null)) return false;
  if (Boolean(A.owner) !== Boolean(B.owner) || (A.owner && !exact(A.owner, B.owner))) return false;
  if (Boolean(A.incarnation) !== Boolean(B.incarnation)
    || (A.incarnation && !sameIncarnation(A.incarnation, B.incarnation))) return false;
  return true;
}

/**
 * The one comparison (see the header). `a` and `b` are whole values; `rule` is 'same', 'proves',
 * 'contradicts' or 'exact'. For 'proves', `a` is the record line and `ctx` its context
 * ({ keyStarts, contested, writer, family }). `ctx.tick` forces the tick of a birth that names none.
 */
function compareIdentity(a, b, rule = 'same', ctx = {}) {
  if (rule === 'proves') return proves(a, identityOf(b), ctx);
  const opts = ctx.tick ? { defaultTick: ctx.tick } : {};
  const A = identityOf(a, opts), B = identityOf(b, opts);
  if (rule === 'same') return same(A, B);
  if (rule === 'contradicts') return contradicts(A, B);
  if (rule === 'exact') return exact(A, B);
  if (rule === 'native') return native(A, B);
  throw new Error(`unknown identity rule ${rule}`);
}

/** The PID and birth as a key, for indexes and output only (never a comparison: compareIdentity). */
const identityKey = (x) => { const v = identityOf(x); return `${v?.pid ?? x?.pid ?? null}/${v?.rawBirth ?? null}`; };
/** Whether `x` is the identity output as `key` (identityKey). */
const namedBy = (x, key) => typeof key === 'string' && identityKey(x) === key;
/** Whether `x` is the process `pid` (a PID read from a parent link or an OS row: a PID alone, not an identity). */
const hasPid = (x, pid) => { const v = identityOf(x); return v?.pid !== null && v?.pid === validPid(pid); };

/** A birth as milliseconds since the epoch (null when unknown). */
function birthMs(x) {
  const v = identityOf(x);
  return v?.birth ? Number((BigInt(v.birth) - EPOCH_FILETIME) / 10000n) : null;
}
/** A birth as a BigInt FILETIME (null when unknown), for comparing with instants. */
function birthFileTime(x) {
  const v = identityOf(x);
  return v?.birth ? BigInt(v.birth) : null;
}
/**
 * The order of two births at `tick` (default: the coarser of their two), -1, 0 or 1, or null when
 * either is unknown. `a` born no later than `b` is order <= 0.
 */
function birthOrder(a, b, tick = null) {
  const A = identityOf(a), B = identityOf(b);
  if (!A?.birth || !B?.birth) return null;
  const t = tick ?? coarser(A, B);
  const x = BigInt(A.birth) / t, y = BigInt(B.birth) / t;
  return x < y ? -1 : x > y ? 1 : 0;
}
/** `a` was born no later than `b` at `tick` (false when either birth is unknown). */
const bornNotAfter = (a, b, tick = null) => { const o = birthOrder(a, b, tick); return o !== null && o <= 0; };
/** `a` was born after `b` at `tick` (false when either birth is unknown). */
const bornAfter = (a, b, tick = null) => { const o = birthOrder(a, b, tick); return o !== null && o > 0; };
/** A FILETIME string as an identity value with only a birth (for ordering a bare FILETIME). */
const bornAt = (fileTime, tick = TICK.EXACT) => identityOf({ creationFileTime: fileTime == null ? null : String(fileTime),
  birthTick: String(tick) });

module.exports = { TICK, isFileTime, identityOf, startIdentity, noteConflict, compareIdentity, identityKey, namedBy, hasPid,
  writerIsOwner, birthMs, birthFileTime, birthOrder, bornNotAfter, bornAfter, bornAt };
