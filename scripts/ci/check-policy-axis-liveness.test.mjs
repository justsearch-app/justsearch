#!/usr/bin/env node
/**
 * Self-test for the policy-axis liveness gate (tempdoc 879 §D.7).
 *
 * These assert the MECHANISM, not the verdict. The gate shipped once with a green verdict reached
 * for the wrong reason: comments were stripped before the reader scan but not before the
 * declaration scan, so a comma inside a `//` comment between two constructor arguments created a
 * phantom argument and shifted every index after it. `advisoryClass` (index 6) was then attributed
 * to `Set.of()` — meaning deleting every real `advisoryClass` declaration would have left the gate
 * green. A test that only asserts "the gate passes on the real tree" cannot see that; these do.
 *
 * Usage: `node scripts/ci/check-policy-axis-liveness.test.mjs`. Exit 0 = OK, 1 = failure.
 */

import { mkdtempSync, writeFileSync, rmSync, readFileSync, existsSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  declaresOptionalPositionally,
  stripComments,
  splitTopLevel,
  parseComponents,
  CATALOG_DIRS,
  catalogSources,
} from './check-policy-axis-liveness.mjs';

let failures = 0;
function ok(name, condition) {
  if (condition) {
    console.log(`  ok   ${name}`);
  } else {
    console.error(`  FAIL ${name}`);
    failures += 1;
  }
}

// The canonical component list, in the order the record declares it. Kept literal rather than
// parsed so a silent reordering of the real record shows up here as a failure to investigate.
const COMPONENTS = [
  { type: 'RiskTier', name: 'risk' },
  { type: 'ConfirmStrategy', name: 'confirm' },
  { type: 'AuditPolicy', name: 'audit' },
  { type: 'RetryPolicy', name: 'retry' },
  { type: 'Set<RequiredCapability>', name: 'requiredCapabilities' },
  { type: 'boolean', name: 'undoSupported' },
  { type: 'Optional<ResourceRef>', name: 'advisoryClass' },
  { type: 'Optional<OperationRef>', name: 'inverseOperationRef' },
  { type: 'Optional<String>', name: 'capabilityFamily' },
  { type: 'OperationKind', name: 'recordKind' },
];
const ADVISORY = COMPONENTS[6];

const dir = mkdtempSync(join(tmpdir(), 'axis-liveness-'));
function fixture(name, body) {
  const path = join(dir, name);
  writeFileSync(path, body, 'utf8');
  return path;
}

try {
  // --- The regression the gate shipped with: a comment comma must not become an argument. ---
  const commentComma = fixture(
    'CommentComma.java',
    `class C { static Object x() { return new OperationPolicy(
        RiskTier.LOW,
        ConfirmStrategy.None.INSTANCE,
        AuditPolicy.NONE,
        // A directory listing is idempotent: replaying it observes the filesystem again,
        // rather than changing it, so a transient hiccup is what auto-retry is for.
        RetryPolicy.autoRetry(2, "core.browse-folders"),
        Set.of(),
        false); } }`,
  );
  ok(
    'a comma inside a // comment does not fake an advisoryClass declaration',
    declaresOptionalPositionally(ADVISORY, [commentComma], COMPONENTS) === false,
  );

  // --- A genuine positional declaration is still found. ---
  const realDeclaration = fixture(
    'RealDeclaration.java',
    `class C { static Object x() { return new OperationPolicy(
        RiskTier.HIGH,
        ConfirmStrategy.Inline.INSTANCE,
        AuditPolicy.METADATA_ONLY,
        RetryPolicy.noRetry(),
        Set.of(),
        true,
        Optional.of(new ResourceRef("core.advisory-operation-completed"))); } }`,
  );
  ok(
    'a real Optional.of(...) at the advisoryClass index is detected',
    declaresOptionalPositionally(ADVISORY, [realDeclaration], COMPONENTS) === true,
  );

  // --- All-empty is the `rateLimit` shape: it must NOT count as declared. ---
  const allEmpty = fixture(
    'AllEmpty.java',
    `class C { static Object x() { return new OperationPolicy(
        RiskTier.LOW, ConfirmStrategy.None.INSTANCE, AuditPolicy.NONE,
        RetryPolicy.noRetry(), Set.of(), false,
        Optional.empty(), Optional.empty(), Optional.empty()); } }`,
  );
  ok(
    'an axis every site passes Optional.empty() for is not declared (the rateLimit shape)',
    declaresOptionalPositionally(ADVISORY, [allEmpty], COMPONENTS) === false,
  );

  // --- A shorter back-compat overload must not mis-attribute a later index. ---
  const shortOverload = fixture(
    'ShortOverload.java',
    `class C { static Object x() { return new OperationPolicy(
        RiskTier.LOW, ConfirmStrategy.None.INSTANCE, AuditPolicy.NONE,
        RetryPolicy.noRetry(), Set.of(), false); } }`,
  );
  ok(
    'a 6-arg overload declares nothing at index 6 rather than reading past the end',
    declaresOptionalPositionally(ADVISORY, [shortOverload], COMPONENTS) === false,
  );

  // --- A block comment between arguments is the same hazard in the other syntax. ---
  const blockComment = fixture(
    'BlockComment.java',
    `class C { static Object x() { return new OperationPolicy(
        RiskTier.LOW, ConfirmStrategy.None.INSTANCE, AuditPolicy.NONE,
        /* tempdoc 550, 560: a note with commas, several of them */
        RetryPolicy.noRetry(), Set.of(), false); } }`,
  );
  ok(
    'a block comment with commas does not fake a declaration either',
    declaresOptionalPositionally(ADVISORY, [blockComment], COMPONENTS) === false,
  );

  // --- Helper-level invariants the above rest on. ---
  ok(
    'stripComments removes // and /* */ content',
    stripComments('a // x, y\nb /* p, q */ c').includes(',') === false,
  );
  ok(
    'splitTopLevel does not split inside nested parens or generics',
    splitTopLevel('a, Set.of(b, c), Map<K, V> d').length === 3,
  );

  // --- The real record still has the shape these fixtures assume. ---
  // Read through the gate's own parser so a record reshuffle is caught here, not in production.
  const realComponents = parseComponents(
    readFileSync(
      'modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/OperationPolicy.java',
      'utf8',
    ),
  );
  ok(
    'the real OperationPolicy record matches the component list these fixtures assume',
    JSON.stringify(realComponents.map((c) => c.name)) ===
      JSON.stringify(COMPONENTS.map((c) => c.name)),
  );
} finally {
  rmSync(dir, { recursive: true, force: true });
}

// Tempdoc 880 — the path literals themselves. Both failure modes below are SILENT: the gate skips
// a stale directory via existsSync and skips a file whose name misses the suffix, so it keeps
// exiting 0 while attributing Optional axes from fewer declaration sites. That is exactly how a
// re-home broke this gate: AgentToolsOperationCatalog moved out of the one hard-coded directory and
// `capabilityFamily` was reported undeclared when it had only become invisible.
for (const dir of CATALOG_DIRS) {
  ok(`catalog directory exists: ${dir}`, existsSync(dir) && statSync(dir).isDirectory());
}
ok(
  'both known Operation catalogs are picked up as declaration sites',
  catalogSources.some((f) => f.endsWith('/CoreOperationCatalog.java')) &&
    catalogSources.some((f) => f.endsWith('/AgentToolsOperationCatalog.java')),
);
ok(
  'every catalog source actually declares an OperationPolicy',
  catalogSources.length > 0 &&
    catalogSources.every((f) => readFileSync(f, 'utf8').includes('new OperationPolicy(')),
);

if (failures > 0) {
  console.error(`check-policy-axis-liveness.test FAIL — ${failures} assertion(s) failed.`);
  process.exit(1);
}
// 8 mechanism assertions + one per catalog directory + 2 over the resolved catalog sources.
console.log(`check-policy-axis-liveness.test OK - ${10 + CATALOG_DIRS.length} assertions passed.`);
