#!/usr/bin/env node
/**
 * Durable-state compatibility gate.
 *
 * StoreCatalog remains the encryption/recoverability authority for its six stores. The governed
 * durableStores register is broader: it records every Shell, Head, Worker, external, derived, and
 * ephemeral state owner that participates in an in-place upgrade.
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { scanPersistenceWriteSites, stripJavaComments } from '../governance/lib/persistence-write-scan.mjs';

const REGISTER = 'governance/store-recoverability.v1.json';
/**
 * The corruptionPolicy vocabulary and the register's count ratchet, deliberately in a SEPARATE file
 * from the register: rows change in parallel with other lanes, and a vocabulary edit colliding with
 * a row edit is a merge conflict for no reason (tempdoc 910 item 2).
 */
const POLICIES = 'governance/store-corruption-policies.v1.json';
/** `#613` or `worktree-resid2-stores` — something a reader can actually chase. */
const AWAITING_ROW_REFERENCE = /#\d+|worktree-[A-Za-z0-9._-]+/;
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

function toIsoDay(now) {
  return (now instanceof Date ? now : new Date(now)).toISOString().slice(0, 10);
}
const OWNERS = new Set(['SHELL', 'HEAD', 'WORKER', 'EXTERNAL']);
const RECOVERABILITY = new Set(['AUTHORED', 'DERIVED', 'MIXED', 'EPHEMERAL']);
const STATUSES = new Set(['READY', 'HARDENING_REQUIRED']);
const UPGRADE_HANDLING = new Set([
  'READ_IN_PLACE',
  'REBUILD',
  'RESET',
  'PRESERVE_EXTERNAL',
]);
/**
 * How a row's declared `ownedPaths` are held to the code that writes them. LITERAL rows are checked
 * segment-by-segment against the string literals in their implementationSources; COMPOSED rows are a
 * STATED exclusion — the path is assembled at runtime, and the row must say why. There is no third
 * value, so "nobody answered the question" is unrepresentable.
 */
const PATH_VERIFICATION = new Set(['LITERAL', 'COMPOSED']);
/**
 * The storage roots a row may declare. Enumerated so an invented or misspelled root is a build
 * failure rather than a free-text string nobody reads.
 *
 * HONEST LIMIT, stated so this is not read as stronger than it is: this catches a root that does not
 * EXIST, not a root that exists and is wrong. `ai-install-attempt-memory` shipped as DATA_DIR when
 * the file is written under AI_HOME (review of PR #604, S1), and both are members of this set — so
 * this check would NOT have caught it. Catching that needs the gate to know which root a row's
 * writer resolves against, which is caller-side information `pathVerification` cannot see.
 */
const ROOTS = new Set([
  'DATA_DIR',
  'AI_HOME',
  'PROGRAM_DATA_OR_DATA_DIR',
  // Tempdoc 909 items 7/8: the user's own documents, under the indexed roots they configured. The
  // app MUTATES them (the agent's file-operations tool moves, copies and deletes there), so there
  // is a durable authority to declare — but the location is the user's, resolved at runtime from
  // the live indexing service, and is under none of the app-owned roots above.
  'USER_INDEXED_ROOTS',
]);
/**
 * Whether a row's bytes are sealed at rest, and if not, why not. Every row answers; the absence of an
 * answer is a build failure. UNSEALED_GAP is the honest label for "no structural reason, just not done";
 * UNSEALED_DERIVED_OS_DISK_ENCRYPTION is the deliberate StoreCatalog Framing.OPAQUE position — a DERIVED,
 * rebuildable store that holds user-derived bytes and is covered by OS disk encryption only; and
 * NOT_APPLICABLE claims there is nothing here worth sealing. All three are claims about content, so all
 * three carry an encryptionNote saying what the file actually contains (tempdoc 879: NOT_APPLICABLE was
 * the one value nobody had to justify, and it was standing in for both "nothing sensitive" and "the
 * user's own document text").
 */
const ENCRYPTION = new Set([
  'SEALED_BY_STORE_CIPHER',
  'UNSEALED_KEY_ROOT',
  'UNSEALED_EXTERNAL_AUTHORITY',
  'UNSEALED_NO_JVM_CIPHER',
  'UNSEALED_DERIVED_OS_DISK_ENCRYPTION',
  'UNSEALED_GAP',
  'NOT_APPLICABLE',
]);
/** The dispositions that are claims about what the bytes hold, and so must say what that is. */
const ENCRYPTION_NOTE_REQUIRED = new Map([
  ['UNSEALED_GAP', 'saying what the plaintext file contains'],
  [
    'UNSEALED_DERIVED_OS_DISK_ENCRYPTION',
    'naming the user-derived content it holds in the clear and why the store is rebuildable',
  ],
  ['NOT_APPLICABLE', 'saying what the file holds and why none of it is worth sealing'],
]);

/** Extract `NAME("dir", StoreRecoverability.CLASS, ...)` entries from StoreCatalog.java. */
export function extractCatalogEntries(javaSrc) {
  const entries = [];
  const re =
    /\b([A-Z][A-Z0-9_]*)\s*\(\s*"([^"]+)"\s*,\s*StoreRecoverability\.(AUTHORED|DERIVED)/g;
  let match;
  while ((match = re.exec(javaSrc)) !== null) {
    entries.push({ constant: match[1], dirName: match[2], recoverability: match[3] });
  }
  return entries;
}

/** Find bare-literal cipher selections instead of StoreCatalog-derived selections. */
export function findHardcodedCipherCalls(javaSrc) {
  const hits = [];
  const re = /storeCipher\(\s*(?:[\w.]*\.)?StoreRecoverability\.(AUTHORED|DERIVED)/g;
  let match;
  while ((match = re.exec(javaSrc)) !== null) hits.push(match[1]);
  return hits;
}

/** Preserve the original StoreCatalog-to-register mirror invariant. */
export function checkParity(catalogEntries, registerStores) {
  const failures = [];
  const catalog = new Map(catalogEntries.map((entry) => [entry.dirName, entry.recoverability]));
  const register = new Map(registerStores.map((entry) => [entry.dirName, entry.recoverability]));

  for (const [dirName, recoverability] of catalog) {
    if (!register.has(dirName)) {
      failures.push(
        `parity: StoreCatalog declares \`${dirName}\` (${recoverability}) but ${REGISTER} has no mirror row.`,
      );
    } else if (register.get(dirName) !== recoverability) {
      failures.push(
        `parity: \`${dirName}\` is ${recoverability} in StoreCatalog but ${register.get(dirName)} in ${REGISTER}.`,
      );
    }
  }
  for (const [dirName, recoverability] of register) {
    if (!catalog.has(dirName)) {
      failures.push(
        `parity: ${REGISTER} mirrors \`${dirName}\` (${recoverability}) but StoreCatalog has no entry.`,
      );
    }
  }
  return failures;
}

/**
 * Every path component that appears inside a double-quoted string literal of a source file: the whole
 * literal, plus each of its slash-separated parts, so `"ui/settings.json"` answers for `ui` and for
 * `settings.json` while `"ui"` answers only for `ui`. Component equality (not substring) is the match
 * rule, so a literal containing `build` can never stand in for the declared segment `ui`.
 *
 * Callers pass ONE source's text at a time (checkPathAgreement requires a single file to answer for a
 * whole declared path); the array form is kept for the union view a caller may still want.
 */
export function literalPathComponents(sources) {
  const components = new Set();
  const re = /"((?:[^"\\\n]|\\.)*)"/g;
  for (const src of sources) {
    let match;
    while ((match = re.exec(src)) !== null) {
      const value = match[1];
      components.add(value);
      for (const part of value.split(/[\\/]/)) if (part) components.add(part);
    }
  }
  return components;
}

/** The declared segments of one owned path that a literal check can speak about (globs cannot). */
export function checkablePathSegments(ownedPath) {
  return String(ownedPath ?? '')
    .split('/')
    .filter((segment) => segment !== '' && !segment.includes('*'));
}

/**
 * Validate the broad durable-state register. The known-gap list is an explicit ratchet: only rows
 * already named there may remain HARDENING_REQUIRED while tempdoc 617 converts them.
 */
export function checkDurableStoreRegister({
  root,
  durableStores,
  knownCompatibilityGaps,
  catalogEntries,
  discoveredWriteSites,
  nonDurableWriteSites = [],
  pendingDurableClassification = null,
  pathExists = existsSync,
  readSource = (absolutePath) => readFileSync(absolutePath, 'utf8'),
}) {
  const failures = [];
  const authoredCatalogDirs = new Set(
    (catalogEntries ?? [])
      .filter((entry) => entry.recoverability === 'AUTHORED')
      .map((entry) => entry.dirName),
  );
  const rows = Array.isArray(durableStores) ? durableStores : [];
  const gaps = new Set(Array.isArray(knownCompatibilityGaps) ? knownCompatibilityGaps : []);
  const ids = new Set();
  const coveredImplementations = new Set();
  // A non-durable classification is a CLAIM ("nothing here survives, so there is no recovery,
  // upgrade or encryption policy to state"), and every other row in this register has to justify
  // its claims. An entry is therefore `{ path, reason }`; the reason is what a reader checks the
  // claim against, and without it the list is a bare allowlist that grows by assertion.
  const classifiedNonDurable = new Set();
  for (const [index, entry] of (nonDurableWriteSites ?? []).entries()) {
    const path = typeof entry === 'string' ? entry : entry?.path;
    const reason = typeof entry === 'string' ? null : entry?.reason;
    if (typeof path !== 'string' || path.trim() === '') {
      failures.push(`nonDurableWriteSites[${index}]: path is required.`);
      continue;
    }
    if (typeof reason !== 'string' || reason.trim() === '') {
      failures.push(
        `nonDurableWriteSites[${index}] (${path}): reason is required — say which paths it writes ` +
          'and why losing them costs only a recomputation.',
      );
    }
    classifiedNonDurable.add(normalize(path));
  }
  const catalogRows = new Map();

  for (const row of rows) {
    const label = row?.id ? `durableStores.${row.id}` : 'durableStores.<missing-id>';
    if (!row?.id) {
      failures.push(`${label}: id is required.`);
      continue;
    }
    if (ids.has(row.id)) failures.push(`${label}: duplicate id.`);
    ids.add(row.id);

    if (!OWNERS.has(row.owner)) failures.push(`${label}: invalid owner \`${row.owner}\`.`);
    if (!RECOVERABILITY.has(row.recoverability)) {
      failures.push(`${label}: invalid recoverability \`${row.recoverability}\`.`);
    }
    if (!STATUSES.has(row.status)) failures.push(`${label}: invalid status \`${row.status}\`.`);
    if (!UPGRADE_HANDLING.has(row.upgradeHandling)) {
      failures.push(`${label}: invalid upgradeHandling \`${row.upgradeHandling}\`.`);
    }
    if (!Array.isArray(row.ownedPaths) || row.ownedPaths.length === 0) {
      failures.push(`${label}: ownedPaths must name at least one exact path or glob.`);
    } else if (
      row.ownedPaths.some((path) => typeof path !== 'string' || path.trim() === '')
    ) {
      failures.push(`${label}: ownedPaths entries must be non-blank strings.`);
    }
    for (const field of ['root', 'format', 'atomicity', 'corruptionPolicy', 'reconciliation']) {
      if (typeof row[field] !== 'string' || row[field].trim() === '') {
        failures.push(`${label}: ${field} is required.`);
      }
    }
    // currentVersion + readableLegacyVersions are required on EVERY row, not just READY
    // READ_IN_PLACE ones, because THIS REGISTER IS A WIRE CONTRACT — see the register's own note.
    // Both consumers read them unconditionally: updater.rs's LocalDurableStore takes
    // `current_version: u32` with no serde default, so one row without it makes the whole embedded
    // register unparseable and validate_store_compatibility rejects EVERY release descriptor; and
    // UpgradeLifecycleContractTest builds its closed owner set with
    // `store.get("currentVersion").asInt()`, which NPEs on a row that omits it. Six rows shipped
    // without them in PR #604 and both consumers broke — the gate was weaker than its readers.
    for (const field of ['currentVersion', 'readableLegacyVersions']) {
      const value = row[field];
      const ok = field === 'currentVersion'
        ? Number.isInteger(value) && value >= 0
        : Array.isArray(value) && value.every((v) => Number.isInteger(v) && v >= 0);
      if (!ok) {
        failures.push(
          `${label}: ${field} is required on every row (a non-negative integer` +
            `${field === 'currentVersion' ? '' : ' array'}). updater.rs deserialises this register ` +
            'with no default and the upgrade reconciliation reads it for the closed owner set, so a ' +
            'row without it breaks release acceptance, not just this gate. Use 0 for bytes with no ' +
            'version envelope, which is what the unversioned rows already do.',
        );
      }
    }

    if (typeof row.root === 'string' && row.root.trim() !== '' && !ROOTS.has(row.root)) {
      failures.push(
        `${label}: unknown root \`${row.root}\` — must be one of ${[...ROOTS].join(', ')}. ` +
          'Add the root here if a genuinely new storage location exists; do not invent a spelling.',
      );
    }

    const sources = Array.isArray(row.implementationSources) ? row.implementationSources : [];
    if (sources.length === 0) failures.push(`${label}: implementationSources must not be empty.`);
    const readableSources = [];
    for (const source of sources) {
      coveredImplementations.add(normalize(source));
      if (!pathExists(resolve(root, source))) failures.push(`${label}: source does not exist: ${source}.`);
      else readableSources.push(source);
    }

    failures.push(...checkPathAgreement({ root, row, label, readableSources, readSource }));
    failures.push(...checkEncryptionDisposition({ row, label, authoredCatalogDirs }));
    if (row.id === 'jobs-db') {
      if (typeof row.versionAuthority !== 'string' || !row.versionAuthority.trim()
          || !pathExists(resolve(root, row.versionAuthority))) {
        failures.push(`${label}: versionAuthority must resolve to the jobs schema source.`);
      } else {
        try {
          const code = stripJavaComments(readSource(resolve(root, row.versionAuthority)),
            { stripLiterals: true });
          // This authority uses positive decimal literals. Reject other Java bases rather
          // than interpreting (for example) octal 017 as decimal 17.
          const declarations = [...code.matchAll(
            /^\s*public\s+static\s+final\s+int\s+TARGET_VERSION\s*=\s*([1-9](?:[\d_]*\d)?)\s*;\s*$/gm)];
          if (declarations.length !== 1) {
            failures.push(`${label}: versionAuthority must declare exactly one literal TARGET_VERSION.`);
          } else {
            const target = Number(declarations[0][1].replaceAll('_', ''));
            if (!Number.isSafeInteger(target) || target <= 0 || row.currentVersion !== target) {
              failures.push(`${label}: currentVersion ${row.currentVersion} disagrees with `
                  + `${row.versionAuthority} TARGET_VERSION ${target}.`);
            }
          }
        } catch (error) {
          failures.push(`${label}: cannot read versionAuthority ${row.versionAuthority}: ${error.message}.`);
        }
      }
    }
    for (const evidence of [...(row.tests ?? []), ...(row.fixtures ?? [])]) {
      if (!pathExists(resolve(root, evidence))) {
        failures.push(`${label}: evidence does not exist: ${evidence}.`);
      }
    }

    if (row.catalogDirName) {
      if (catalogRows.has(row.catalogDirName)) {
        failures.push(`${label}: duplicate catalogDirName \`${row.catalogDirName}\`.`);
      }
      catalogRows.set(row.catalogDirName, row);
    }

    if (row.status === 'READY') {
      if (
        (row.recoverability === 'AUTHORED' || row.recoverability === 'MIXED') &&
        row.corruptionPolicy === 'SILENT_EMPTY'
      ) {
        failures.push(`${label}: authored/mixed state cannot use SILENT_EMPTY recovery.`);
      }
      if (
        row.writeMode === 'FULL_REWRITE' &&
        row.upgradeHandling === 'READ_IN_PLACE' &&
        !['ATOMIC_REPLACE', 'TRANSACTIONAL'].includes(row.atomicity)
      ) {
        failures.push(`${label}: a READY full rewrite must be atomic or transactional.`);
      }
      if (row.upgradeHandling === 'READ_IN_PLACE' && Number(row.currentVersion) <= 0) {
        failures.push(`${label}: READ_IN_PLACE state must have a positive currentVersion.`);
      }
      if (row.upgradeHandling === 'READ_IN_PLACE') {
        if (!row.versionAuthority) failures.push(`${label}: versionAuthority is required.`);
        if (!row.futureVersionRefusalTest) {
          failures.push(`${label}: futureVersionRefusalTest is required.`);
        } else if (!pathExists(resolve(root, row.futureVersionRefusalTest))) {
          failures.push(
            `${label}: futureVersionRefusalTest does not exist: ${row.futureVersionRefusalTest}.`,
          );
        }
      }
      if (
        ['REBUILD', 'RESET', 'PRESERVE_EXTERNAL'].includes(row.upgradeHandling)
        && (row.tests ?? []).length === 0
      ) {
        failures.push(`${label}: ${row.upgradeHandling} requires a recovery/preservation test.`);
      }
    }
  }

  for (const id of gaps) {
    const row = rows.find((candidate) => candidate.id === id);
    if (!row) failures.push(`knownCompatibilityGaps names missing durable store \`${id}\`.`);
    else if (row.status !== 'HARDENING_REQUIRED') {
      failures.push(`knownCompatibilityGaps names READY durable store \`${id}\`.`);
    }
  }
  for (const row of rows) {
    if (row.status === 'HARDENING_REQUIRED' && !gaps.has(row.id)) {
      failures.push(
        `durableStores.${row.id}: HARDENING_REQUIRED is not ratcheted in knownCompatibilityGaps.`,
      );
    }
  }

  // The third answer, and the narrowest: a discovered site that IS durable but has no row yet.
  // It cannot be nonDurableWriteSites (that would be the scratch escape hatch the note forbids) and
  // it cannot be a durableStores row either, because a row is a RUNTIME commitment — updater.rs
  // deserialises it and UpgradeReconciliationProbe refuses to attest an upgrade when any row is not
  // READY. Every entry names its blocker, and the list is capped so it cannot become a parking lot.
  const classifiedPending = new Set();
  if (pendingDurableClassification !== null && pendingDurableClassification !== undefined) {
    const entries = Array.isArray(pendingDurableClassification.entries)
      ? pendingDurableClassification.entries
      : null;
    const cap = pendingDurableClassification.cap;
    if (!entries) {
      failures.push('pendingDurableClassification.entries must be an array.');
    } else if (!Number.isInteger(cap) || cap < 0) {
      failures.push('pendingDurableClassification.cap must be a non-negative integer ratchet.');
    } else {
      if (entries.length > cap) {
        failures.push(
          `pendingDurableClassification holds ${entries.length} entries against a cap of ${cap}. ` +
            'Resolve one into durableStores, or raise the cap deliberately and say why — this list ' +
            'exists to shrink.',
        );
      }
      for (const [index, entry] of entries.entries()) {
        const label = `pendingDurableClassification[${index}]`;
        for (const field of ['path', 'state', 'blocker']) {
          if (typeof entry?.[field] !== 'string' || entry[field].trim() === '') {
            failures.push(`${label}: ${field} is required.`);
          }
        }
        if (typeof entry?.path === 'string' && entry.path.trim() !== '') {
          classifiedPending.add(normalize(entry.path));
          if (!pathExists(resolve(root, entry.path))) {
            failures.push(`${label}: path does not exist: ${entry.path}.`);
          }
        }
      }
    }
  }

  for (const source of discoveredWriteSites ?? []) {
    const normalized = normalize(source);
    if (
      !coveredImplementations.has(normalized)
      && !classifiedNonDurable.has(normalized)
      && !classifiedPending.has(normalized)
    ) {
      failures.push(`coverage: persistence write site is unclassified: ${source}.`);
    }
  }
  for (const source of classifiedNonDurable) {
    if (!pathExists(resolve(root, source))) {
      failures.push(`coverage: classified non-durable write site does not exist: ${source}.`);
    }
  }

  for (const catalogEntry of catalogEntries ?? []) {
    const row = catalogRows.get(catalogEntry.dirName);
    if (!row) {
      failures.push(
        `coverage: StoreCatalog.${catalogEntry.constant} has no durableStores row with catalogDirName \`${catalogEntry.dirName}\`.`,
      );
    } else if (row.recoverability !== catalogEntry.recoverability) {
      failures.push(
        `coverage: durableStores.${row.id} recoverability disagrees with StoreCatalog.${catalogEntry.constant}.`,
      );
    }
  }

  return failures;
}

/**
 * Hold a row's declared paths to the code that writes them. Without this the register could name any
 * path at all and stay green — which is how four rows drifted from their real on-disk locations
 * (tempdoc 879). A row opts out only by SAYING so: pathVerification COMPOSED plus a note.
 *
 * ONE source file must answer for a whole declared path. Unioning the literals of every listed source
 * made LITERAL satisfiable by adding an unrelated file that happens to contain the missing word — the
 * row goes green without any file actually writing the path. Requiring a single file to hold every
 * segment means the match is evidence of a writer, not of a well-stocked source list.
 */
export function checkPathAgreement({ root, row, label, readableSources, readSource }) {
  const failures = [];
  if (!PATH_VERIFICATION.has(row.pathVerification)) {
    failures.push(
      `${label}: pathVerification is required and must be one of ${[...PATH_VERIFICATION].join(', ')}.`,
    );
    return failures;
  }
  if (row.pathVerification === 'COMPOSED') {
    if (typeof row.pathVerificationNote !== 'string' || row.pathVerificationNote.trim() === '') {
      failures.push(
        `${label}: pathVerification COMPOSED requires a pathVerificationNote saying which segments are assembled at runtime and by what.`,
      );
    }
    return failures;
  }

  const perSource = [];
  for (const source of readableSources) {
    try {
      perSource.push({ source, components: literalPathComponents([readSource(resolve(root, source))]) });
    } catch (error) {
      failures.push(`${label}: cannot read implementation source ${source}: ${error.message}.`);
    }
  }
  for (const ownedPath of row.ownedPaths ?? []) {
    const segments = checkablePathSegments(ownedPath);
    if (segments.length === 0) continue;
    if (perSource.some(({ components }) => segments.every((segment) => components.has(segment)))) {
      continue;
    }
    const best = perSource
      .map(({ source, components }) => ({
        source,
        missing: segments.filter((segment) => !components.has(segment)),
      }))
      .sort((a, b) => a.missing.length - b.missing.length)[0];
    const detail = best
      ? `closest source ${best.source} is missing ${best.missing.map((s) => `\`${s}\``).join(', ')}`
      : 'no readable implementation source';
    failures.push(
      `${label}: ownedPaths declares \`${ownedPath}\` but no SINGLE implementation source contains ` +
        `every one of its string literals ${segments.map((s) => `\`${s}\``).join(', ')} ` +
        `(searched: ${readableSources.join(', ') || 'none'}; ${detail}). ` +
        `Either the declared path is wrong, the writing source is missing from implementationSources, ` +
        `or the path is assembled at runtime — say so with pathVerification COMPOSED + pathVerificationNote. ` +
        `Adding a second file that merely mentions the missing segment does not make the path literal.`,
    );
  }
  return failures;
}

/**
 * Every row states whether its bytes are sealed at rest and, when they are not, which reason applies.
 * A store the StoreCatalog calls AUTHORED must be sealed; every disposition that is a claim about the
 * file's content must name that content. This records the disposition — it does not change behavior.
 */
export function checkEncryptionDisposition({ row, label, authoredCatalogDirs }) {
  const failures = [];
  if (!ENCRYPTION.has(row.encryption)) {
    failures.push(
      `${label}: encryption is required and must be one of ${[...ENCRYPTION].join(', ')}.`,
    );
    return failures;
  }
  const noteObligation = ENCRYPTION_NOTE_REQUIRED.get(row.encryption);
  if (
    noteObligation
    && (typeof row.encryptionNote !== 'string' || row.encryptionNote.trim() === '')
  ) {
    failures.push(
      `${label}: encryption ${row.encryption} requires an encryptionNote ${noteObligation}.`,
    );
  }

  const paths = (row.ownedPaths ?? []).map(normalize);
  const insideAuthoredCatalogDir =
    paths.length > 0
    && paths.every((path) => [...authoredCatalogDirs].some((dir) => path.startsWith(`${dir}/`)));
  const isCatalogAuthored = Boolean(row.catalogDirName) && authoredCatalogDirs.has(row.catalogDirName);
  if ((isCatalogAuthored || insideAuthoredCatalogDir) && row.encryption !== 'SEALED_BY_STORE_CIPHER') {
    failures.push(
      `${label}: this row is an AUTHORED StoreCatalog store (or lives entirely inside one) and must ` +
        `declare encryption SEALED_BY_STORE_CIPHER, not \`${row.encryption}\`.`,
    );
  }
  return failures;
}

/**
 * Holds every row's `corruptionPolicy` to the closed vocabulary in `store-corruption-policies.v1.json`.
 *
 * Kept OUT of `checkDurableStoreRegister` on purpose: that function is the row-shape checker its unit
 * tests drive with invented rows, and folding a repo-wide vocabulary into it would make those fixtures
 * depend on the real register's spellings.
 *
 * @param {{ durableStores: Array<object>, policies: Record<string, string>|undefined }} input
 * @returns {string[]} failures
 */
export function checkCorruptionPolicyVocabulary({ durableStores, policies, awaitingRow = {}, now = new Date() }) {
  const failures = [];
  if (policies === null || typeof policies !== 'object' || Array.isArray(policies)) {
    failures.push(
      `${POLICIES}: \`policies\` must be an object mapping each corruptionPolicy value to a one-line meaning.`,
    );
    return failures;
  }
  const awaiting = awaitingRow && typeof awaitingRow === 'object' && !Array.isArray(awaitingRow)
    ? awaitingRow
    : {};
  const known = new Set(Object.keys(policies));
  for (const [value, meaning] of Object.entries(policies)) {
    if (typeof meaning !== 'string' || meaning.trim() === '') {
      failures.push(`${POLICIES}: \`${value}\` has no meaning. Every value states what happens to the bytes.`);
    }
  }
  const used = new Set();
  for (const row of durableStores ?? []) {
    const value = row?.corruptionPolicy;
    if (typeof value !== 'string' || value.trim() === '') continue; // shape checker owns this
    used.add(value);
    if (!known.has(value)) {
      failures.push(
        `durableStores.${row.id ?? '<missing-id>'}: unknown corruptionPolicy \`${value}\`. ` +
          `The vocabulary is closed and lives in ${POLICIES} — add the value there with a one-line ` +
          'meaning describing the OBSERVABLE outcome for the user\'s bytes (kept / rewritten / ' +
          'deleted / refused), in the same commit as this row. Do not add a near-synonym of an ' +
          'existing value to avoid editing the row; see that file\'s `extensionProcedure`.',
      );
    }
  }
  // A vocabulary that only ever grows becomes a list of spellings nobody uses, which is the same
  // false authority a stale register is. An unused value is a failure, and deleting it is the fix.
  // The ONE exception is a value declared ahead of a row landing in another in-flight branch, which
  // must say so in `awaitingRow` — and that marker is self-retiring: once the row lands, a stale
  // marker is itself a failure, so the scaffolding cannot outlive its reason.
  for (const value of known) {
    if (!used.has(value) && !(value in awaiting)) {
      failures.push(
        `${POLICIES}: \`${value}\` is declared but no durableStores row uses it. Delete it — a ` +
          'vocabulary entry with no row is a spelling waiting to be picked by mistake. If the row ' +
          'is landing in another in-flight branch, add the value to `awaitingRow` naming that PR ' +
          'instead of leaving it unexplained.',
      );
    }
    if (used.has(value) && value in awaiting) {
      failures.push(
        `${POLICIES}: \`${value}\` is in \`awaitingRow\` but a durableStores row now uses it. ` +
          'Remove the awaitingRow entry (keep the policy) — it was scaffolding for a row that has ' +
          'since landed.',
      );
    }
  }
  const today = toIsoDay(now);
  for (const [value, marker] of Object.entries(awaiting)) {
    const label = `${POLICIES}: \`awaitingRow.${value}\``;
    if (!known.has(value)) {
      failures.push(
        `${POLICIES}: \`${value}\` is in \`awaitingRow\` but has no entry in \`policies\`. ` +
          'A forward declaration still needs its one-line meaning.',
      );
    }
    const reason = marker && typeof marker === 'object' ? marker.reason : marker;
    const until = marker && typeof marker === 'object' ? marker.until : undefined;

    if (typeof reason !== 'string' || reason.trim() === '') {
      failures.push(`${label} must carry a \`reason\` naming the PR or branch whose row will use it.`);
    } else if (!AWAITING_ROW_REFERENCE.test(reason)) {
      failures.push(
        `${label}.reason does not name a PR or branch. Cite one as \`#613\` or ` +
          '`worktree-<name>` so the marker can be chased to something real — "landing soon" is how ' +
          'a forward declaration becomes permanent.',
      );
    }

    // Without an expiry the marker only retires on the SUCCESS path (a row lands and the
    // row-landed branch above fires). If the referenced PR is abandoned, nothing ever removes it
    // and the value sits in the vocabulary forever — exactly the outliving-its-reason this
    // register is meant to prevent.
    if (typeof until !== 'string' || !ISO_DAY.test(until)) {
      failures.push(
        `${label} must carry an ISO \`until\` date (YYYY-MM-DD). A marker with no expiry only ` +
          'retires if its PR lands; if that PR is abandoned it becomes permanent.',
      );
    } else if (until < today) {
      failures.push(
        `${label} expired on ${until} (today is ${today}). Either the row landed — delete the ` +
          `marker — or it did not: delete \`${value}\` from \`policies\` too. Extending the date ` +
          'is a third option only if the PR is genuinely still in flight, and it needs saying why.',
      );
    }
  }
  return failures;
}

/**
 * The register's count ratchet. Both numbers are pinned OUTSIDE the register they describe, because
 * `pendingDurableClassification.cap` previously called itself a ratchet while sitting in the same
 * file as the entries it capped — one commit could add a pending entry and raise the cap forbidding
 * it (tempdoc 910 item 2).
 *
 * `durableStoreRows` is a FLOOR, not an equality: adding rows is the register covering more of the
 * tree, and a lane adding rows in parallel must not red this gate. A row DISAPPEARING is the event
 * worth failing on.
 *
 * @returns {string[]} failures
 */
export function checkCountRatchet({ durableStores, pendingDurableClassification, ratchet }) {
  const failures = [];
  if (ratchet === null || typeof ratchet !== 'object') {
    failures.push(`${POLICIES}: \`ratchet\` block is missing.`);
    return failures;
  }
  const { durableStoreRows, pendingDurableClassificationCap } = ratchet;
  const rows = Array.isArray(durableStores) ? durableStores.length : null;

  if (!Number.isInteger(durableStoreRows) || durableStoreRows < 0) {
    failures.push(`${POLICIES}: \`ratchet.durableStoreRows\` must be a non-negative integer.`);
  } else if (rows !== null && rows < durableStoreRows) {
    failures.push(
      `durableStores holds ${rows} rows against a pinned floor of ${durableStoreRows}. A durable ` +
        'authority does not stop existing quietly: if a store was genuinely retired, lower ' +
        `\`ratchet.durableStoreRows\` in ${POLICIES} in the SAME commit that removes the row, and ` +
        'say in the commit message where its bytes went.',
    );
  }

  if (!Number.isInteger(pendingDurableClassificationCap) || pendingDurableClassificationCap < 0) {
    failures.push(`${POLICIES}: \`ratchet.pendingDurableClassificationCap\` must be a non-negative integer.`);
  } else if (pendingDurableClassification) {
    const cap = pendingDurableClassification.cap;
    const entries = Array.isArray(pendingDurableClassification.entries)
      ? pendingDurableClassification.entries.length
      : null;
    if (Number.isInteger(cap) && cap > pendingDurableClassificationCap) {
      failures.push(
        `pendingDurableClassification.cap is ${cap} against a pinned ceiling of ` +
          `${pendingDurableClassificationCap}. The cap cannot be raised in the same file as the ` +
          `entries it caps — raise \`ratchet.pendingDurableClassificationCap\` in ${POLICIES} too, ` +
          'and add a `bumps` entry naming which store forced it and what its blocker is.',
      );
    }
    if (entries !== null && entries > pendingDurableClassificationCap) {
      failures.push(
        `pendingDurableClassification holds ${entries} entries against the pinned ceiling of ` +
          `${pendingDurableClassificationCap}. Resolve one into durableStores, or raise the pin in ` +
          `${POLICIES} deliberately and say why — this list exists to shrink.`,
      );
    }
  }
  return failures;
}

/**
 * Loads the vocabulary file with a remedy-bearing failure instead of a raw JSON.parse stack. Every
 * other failure this gate emits names what to do; a missing or malformed vocabulary is the one an
 * author is MOST likely to hit while extending it, so it is the last one that should surface as an
 * unhandled exception.
 *
 * @returns {{policies: object, ratchet: object, awaitingRow: object}}
 */
export function loadPolicies(root, read = (p) => readFileSync(p, 'utf8')) {
  const path = resolve(root, POLICIES);
  let raw;
  try {
    raw = read(path);
  } catch (error) {
    console.error(
      `store-recoverability gate FAILED:\n  - ${POLICIES} could not be read (${error.code ?? error.message}). ` +
        'It holds the closed corruptionPolicy vocabulary and this register\'s count ratchet; the gate ' +
        'cannot check a row against a vocabulary it cannot load, and defaulting to "anything goes" ' +
        'would silently retire the check. Restore the file from git rather than recreating it by hand.',
    );
    process.exit(1);
  }
  try {
    return JSON.parse(raw);
  } catch (error) {
    console.error(
      `store-recoverability gate FAILED:\n  - ${POLICIES} is not valid JSON (${error.message}). ` +
        'Fix the syntax — most often a trailing comma after the last policy, or an unescaped ' +
        'quote inside a meaning string.',
    );
    process.exit(1);
  }
}

function main() {
  const root = process.cwd();
  const register = JSON.parse(readFileSync(resolve(root, REGISTER), 'utf8'));
  const policies = loadPolicies(root);
  const catalogEntries = extractCatalogEntries(
    readFileSync(resolve(root, register.catalog.file), 'utf8'),
  );
  if (catalogEntries.length === 0) {
    console.error('store-recoverability gate FAILED: no StoreCatalog entries were parsed.');
    process.exit(1);
  }

  const failures = [
    ...checkParity(catalogEntries, register.stores ?? []),
    ...checkDurableStoreRegister({
      root,
      durableStores: register.durableStores,
      knownCompatibilityGaps: register.knownCompatibilityGaps,
      catalogEntries,
      discoveredWriteSites: scanPersistenceWriteSites(root),
      nonDurableWriteSites: register.nonDurableWriteSites,
      pendingDurableClassification: register.pendingDurableClassification,
    }),
    ...checkCorruptionPolicyVocabulary({
      durableStores: register.durableStores,
      policies: policies.policies,
      awaitingRow: policies.awaitingRow,
    }),
    ...checkCountRatchet({
      durableStores: register.durableStores,
      pendingDurableClassification: register.pendingDurableClassification,
      ratchet: policies.ratchet,
    }),
  ];

  for (const site of register.constructionSites ?? []) {
    const hits = findHardcodedCipherCalls(readFileSync(resolve(root, site), 'utf8'));
    if (hits.length > 0) {
      failures.push(
        `no-hardcode: ${site} selects a cipher with a bare StoreRecoverability.${hits[0]} literal.`,
      );
    }
  }

  if (failures.length > 0) {
    console.error(
      'store-recoverability gate FAILED:\n' + failures.map((failure) => `  - ${failure}`).join('\n'),
    );
    process.exit(1);
  }

  console.log(
    `store-recoverability gate OK - ${catalogEntries.length} catalog stores and ` +
      `${register.durableStores.length} durable state authorities are registered ` +
      `(floor ${policies.ratchet.durableStoreRows}), across ` +
      `${Object.keys(policies.policies).length - Object.keys(policies.awaitingRow ?? {}).length} ` +
      `corruption policies in use + ${Object.keys(policies.awaitingRow ?? {}).length} awaiting a row.`,
  );
}

function normalize(path) {
  return String(path ?? '').replace(/\\/g, '/');
}

if (
  process.argv[1] &&
  process.argv[1].replace(/\\/g, '/').endsWith('check-store-recoverability.mjs')
) {
  main();
}
