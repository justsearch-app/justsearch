/**
 * Tests for the surface-composition gate (scripts/ci/check-surface-composition.mjs).
 *
 * The FE↔Java parity leg (tempdoc 852 S0) is a safety armed four slices before the flip it protects,
 * which is exactly the shape that ships inert: nobody exercises it until the day it matters. These
 * exercise the BITE — an audience mismatch and a placement mismatch each failing, agreement passing,
 * one-sided declarations correctly out of scope, a commented-out declaration not counting — plus the
 * pre-existing-drift ledger's two self-retiring failure modes, and leg 1's four composition rules so
 * the parity work cannot silently regress them.
 *
 * Run: `node scripts/ci/check-surface-composition.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { expandCoreSurfaceMetadata } from '../lib/core-surface-declarations.mjs';

import { run, parseJavaSurfaces, parseCorePluginSurfaces } from './check-surface-composition.mjs';

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (error) {
    failures.push(error.message);
  }
};

const PATHS = {
  surfaceCatalog: 'modules/app-observability/.../CoreSurfaceCatalog.java',
  corePlugin: 'modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts',
};

/** The `SurfaceRef` constant a `new Surface(...)` declaration is keyed on. */
const javaSurfaceRef = ({ constant, id }) =>
  `  public static final SurfaceRef ${constant} = new SurfaceRef("${id}");\n`;

const javaDefinition = ({ constant, audience = 'USER', placement = 'RAIL', members = null }) => `
          new Surface(
              ${constant},
              Presentation.of(new I18nKey("a"), new I18nKey("b")),
              Audience.${audience},
              Placement.${placement},
              new SurfaceConsumes(Set.of(), Set.of(), Set.of(), Set.<DiagnosticChannelRef>of()),
              MOUNT_TAG,
              Provenance.core("1.0"))${members === null ? '' : `\n              .withMembers(List.of(${members}))`},
`;

/** Assemble a whole catalog file from surface specs. */
const javaCatalog = (specs) =>
  specs.map(javaSurfaceRef).join('')
  + '\n  private static final List<Surface> DEFINITIONS =\n      List.of(\n'
  + specs.map(javaDefinition).join('')
  + '      );\n';

/** Assemble a whole CorePlugin.ts from contribution specs. */
const corePlugin = (specs) =>
  'const CORE_SURFACES: PluginSurfaceContribution[] = [\n'
  + specs
    .map(
      (s) => `  {
    id: '${s.id}',
    mountTag: 'jf-x',
    audience: '${s.audience}',
    placement: '${s.placement}',
  },
`,
    )
    .join('')
  + '];\n';

const go = (specs, feSpecs, ledger = []) =>
  run({ javaSource: javaCatalog(specs), pluginSource: corePlugin(feSpecs), paths: PATHS, ledger });

// --- parsers ---------------------------------------------------------------
{
  const surfaces = parseJavaSurfaces(
    javaCatalog([{ constant: 'A_SURFACE_ID', id: 'core.a', audience: 'DEVELOPER', placement: 'DEEPLINK' }]),
  );
  ok(
    'a Java declaration yields its id, audience and placement',
    surfaces.length === 1
      && surfaces[0].id === 'core.a'
      && surfaces[0].audience === 'DEVELOPER'
      && surfaces[0].placement === 'DEEPLINK',
  );
}
{
  const fe = parseCorePluginSurfaces(
    corePlugin([
      { id: 'core.a', audience: 'DEVELOPER', placement: 'DEEPLINK' },
      { id: 'core.b', audience: 'USER', placement: 'RAIL' },
    ]),
  );
  ok(
    "each contribution's own fields are read, not a neighbour's",
    fe.get('core.a').audience === 'DEVELOPER'
      && fe.get('core.a').placement === 'DEEPLINK'
      && fe.get('core.b').audience === 'USER'
      && fe.get('core.b').placement === 'RAIL',
  );
}

// --- LEG 2: parity ---------------------------------------------------------
const AGREE_JAVA = [{ constant: 'A_SURFACE_ID', id: 'core.a', audience: 'DEVELOPER', placement: 'DEEPLINK' }];
const AGREE_FE = [{ id: 'core.a', audience: 'DEVELOPER', placement: 'DEEPLINK' }];

{
  const r = go(AGREE_JAVA, AGREE_FE);
  ok('agreement on both fields passes', r.failures.length === 0 && r.warnings.length === 0);
  ok('an agreeing surface is counted as compared', r.compared === 1);
}
{
  const r = go(AGREE_JAVA, [{ id: 'core.a', audience: 'USER', placement: 'DEEPLINK' }]);
  ok('an audience mismatch FAILS', r.failures.length === 1);
  ok(
    'the audience failure names both files and both values',
    r.failures[0].includes(PATHS.corePlugin)
      && r.failures[0].includes(PATHS.surfaceCatalog)
      && r.failures[0].includes("'USER'")
      && r.failures[0].includes("'DEVELOPER'")
      && r.failures[0].includes('audience'),
  );
  ok(
    'the failure carries the rationale — a one-sided flip is invisible to the other two gates',
    r.failures[0].includes('ONE-SIDED')
      && r.failures[0].includes('interaction-surface')
      && r.failures[0].includes('check-window-cutover')
      && r.failures[0].includes('852'),
  );
}
{
  const r = go(AGREE_JAVA, [{ id: 'core.a', audience: 'DEVELOPER', placement: 'RAIL' }]);
  ok('a placement mismatch FAILS', r.failures.length === 1 && r.failures[0].includes('placement'));
  ok(
    'the placement failure names both values',
    r.failures[0].includes("'RAIL'") && r.failures[0].includes("'DEEPLINK'"),
  );
}
{
  const r = go(AGREE_JAVA, [{ id: 'core.a', audience: 'USER', placement: 'RAIL' }]);
  ok('both fields disagreeing produces one failure per field', r.failures.length === 2);
}
{
  // The real hazard, in miniature: v3 flipped to USER/RAIL in the FE only, while the Java catalog still
  // carries the developer deeplink. This is the exact state check-window-cutover would call "complete".
  const r = go(
    [{ constant: 'V3_SURFACE_ID', id: 'core.search-v3-surface', audience: 'DEVELOPER', placement: 'DEEPLINK' }],
    [{ id: 'core.search-v3-surface', audience: 'USER', placement: 'RAIL' }],
  );
  ok(
    'a one-sided Search v3 promotion FAILS on both fields',
    r.failures.length === 2 && r.failures.every((f) => f.includes('core.search-v3-surface')),
  );
}

// --- LEG 2: what is NOT in scope -------------------------------------------
{
  const r = go(AGREE_JAVA, [...AGREE_FE, { id: 'core.fe-only', audience: 'USER', placement: 'RAIL' }]);
  ok('an FE-only surface is not parity-checked', r.failures.length === 0 && r.compared === 1);
}
{
  const r = go(
    [...AGREE_JAVA, { constant: 'J_SURFACE_ID', id: 'core.java-only', audience: 'USER', placement: 'RAIL' }],
    AGREE_FE,
  );
  ok('a Java-only surface is not parity-checked', r.failures.length === 0 && r.compared === 1);
}

// --- LEG 2: comments -------------------------------------------------------
{
  const pluginSource = corePlugin(AGREE_FE).replace(
    "audience: 'DEVELOPER'",
    "// audience: 'USER',\n    audience: 'DEVELOPER'",
  );
  const r = run({ javaSource: javaCatalog(AGREE_JAVA), pluginSource, paths: PATHS, ledger: [] });
  ok('a commented-out FE audience does not count as a declaration', r.failures.length === 0);
}
{
  const pluginSource = corePlugin(AGREE_FE).replace(
    "audience: 'DEVELOPER'",
    "/* audience: 'USER', */\n    audience: 'DEVELOPER'",
  );
  const r = run({ javaSource: javaCatalog(AGREE_JAVA), pluginSource, paths: PATHS, ledger: [] });
  ok('a block-commented FE audience does not count either', r.failures.length === 0);
}
{
  // A commented-out WHOLE registration must not create a phantom pair to compare against. It is placed
  // AFTER the live one on purpose: a later parse of the same id would otherwise overwrite the real
  // values, so this fails without comment-stripping rather than passing for the wrong reason.
  const pluginSource =
    corePlugin(AGREE_FE) + "\n// { id: 'core.a', audience: 'USER', placement: 'RAIL' },\n";
  const r = run({ javaSource: javaCatalog(AGREE_JAVA), pluginSource, paths: PATHS, ledger: [] });
  ok('a commented-out FE registration is ignored entirely', r.failures.length === 0 && r.compared === 1);
}
{
  const javaSource = javaCatalog(AGREE_JAVA).replace(
    'Audience.DEVELOPER',
    '// Audience.USER — discussed, not declared\n              Audience.DEVELOPER',
  );
  const r = run({ javaSource, pluginSource: corePlugin(AGREE_FE), paths: PATHS, ledger: [] });
  ok('a commented-out Java audience does not count as a declaration', r.failures.length === 0);
}

// --- LEG 2: the pre-existing-drift ledger ----------------------------------
const LEDGER = [
  {
    id: 'core.a',
    corePlugin: { audience: 'OPERATOR', placement: 'DEEPLINK' },
    surfaceCatalog: { audience: 'USER', placement: 'DEEPLINK' },
    note: 'recorded for the test.',
  },
];
{
  const r = go(
    [{ constant: 'A_SURFACE_ID', id: 'core.a', audience: 'USER', placement: 'DEEPLINK' }],
    [{ id: 'core.a', audience: 'OPERATOR', placement: 'DEEPLINK' }],
    LEDGER,
  );
  ok('a recorded pre-existing drift WARNS instead of failing', r.failures.length === 0 && r.warnings.length === 1);
  ok('the warning still names both values', r.warnings[0].includes("'OPERATOR'") && r.warnings[0].includes("'USER'"));
}
{
  const r = go(
    [{ constant: 'A_SURFACE_ID', id: 'core.a', audience: 'USER', placement: 'DEEPLINK' }],
    [{ id: 'core.a', audience: 'DEVELOPER', placement: 'DEEPLINK' }],
    LEDGER,
  );
  ok(
    'drifting FURTHER than the recorded pair FAILS — the ledger exempts one exact pair, not an id',
    r.failures.length === 1 && r.failures[0].includes('CHANGED'),
  );
}
{
  const r = go(
    [{ constant: 'A_SURFACE_ID', id: 'core.a', audience: 'USER', placement: 'DEEPLINK' }],
    [{ id: 'core.a', audience: 'USER', placement: 'DEEPLINK' }],
    LEDGER,
  );
  ok(
    'a settled drift FAILS until its ledger entry is deleted — the exemption retires itself',
    r.failures.length === 1 && r.failures[0].includes('DELETE'),
  );
}
{
  const r = go(AGREE_JAVA, AGREE_FE, [
    { id: 'core.gone', corePlugin: { audience: 'USER', placement: 'RAIL' }, surfaceCatalog: { audience: 'OPERATOR', placement: 'RAIL' }, note: 'x' },
  ]);
  ok(
    'a ledger entry for a pair that no longer exists in both files FAILS as residue',
    r.failures.length === 1 && r.failures[0].includes('core.gone'),
  );
}

// --- LEG 1: composition (regression) ---------------------------------------
{
  const javaSource = javaCatalog([
    { constant: 'HOST_SURFACE_ID', id: 'core.host', placement: 'RAIL', members: 'MEMBER_SURFACE_ID' },
    { constant: 'MEMBER_SURFACE_ID', id: 'core.member', placement: 'DEEPLINK' },
  ]);
  const r = run({ javaSource, pluginSource: null, paths: PATHS, ledger: [] });
  ok(
    'a host with one DEEPLINK member passes and is counted',
    r.failures.length === 0 && r.hostCount === 1 && r.memberCount === 1,
  );
}
{
  const javaSource = javaCatalog([
    { constant: 'HOST_SURFACE_ID', id: 'core.host', placement: 'RAIL', members: 'MEMBER_SURFACE_ID' },
    { constant: 'MEMBER_SURFACE_ID', id: 'core.member', placement: 'RAIL' },
  ]);
  const r = run({ javaSource, pluginSource: null, paths: PATHS, ledger: [] });
  ok('a member that is ALSO a RAIL surface fails (two homes)', r.failures.length === 1 && r.failures[0].includes('one home'));
}
{
  const javaSource = javaCatalog([
    { constant: 'HOST_SURFACE_ID', id: 'core.host', placement: 'RAIL', members: 'HOST_SURFACE_ID' },
  ]);
  const r = run({ javaSource, pluginSource: null, paths: PATHS, ledger: [] });
  ok('a surface hosting itself fails', r.failures.some((f) => f.includes('cannot host itself')));
}
{
  const javaSource = javaCatalog([
    { constant: 'HOST_SURFACE_ID', id: 'core.host', placement: 'RAIL', members: 'new SurfaceRef("core.nowhere")' },
  ]);
  const r = run({ javaSource, pluginSource: null, paths: PATHS, ledger: [] });
  ok('a dangling member ref fails', r.failures.length === 1 && r.failures[0].includes('dangling'));
}
{
  // 578 Option A — a member declared only in the FE contributions resolves, and does not dangle.
  const javaSource = javaCatalog([
    { constant: 'HOST_SURFACE_ID', id: 'core.host', placement: 'RAIL', members: 'new SurfaceRef("core.fe-only")' },
  ]);
  const r = run({
    javaSource,
    pluginSource: corePlugin([{ id: 'core.fe-only', audience: 'USER', placement: 'DEEPLINK' }]),
    paths: PATHS,
    ledger: [],
  });
  ok('a CorePlugin-contributed member resolves against the merged id set', r.failures.length === 0);
}
{
  const javaSource = javaCatalog([
    { constant: 'HOST_A_SURFACE_ID', id: 'core.host-a', placement: 'RAIL', members: 'MEMBER_SURFACE_ID' },
    { constant: 'HOST_B_SURFACE_ID', id: 'core.host-b', placement: 'RAIL', members: 'MEMBER_SURFACE_ID' },
    { constant: 'MEMBER_SURFACE_ID', id: 'core.member', placement: 'DEEPLINK' },
  ]);
  const r = run({ javaSource, pluginSource: null, paths: PATHS, ledger: [] });
  ok('a member hosted by two hosts fails', r.failures.length === 1 && r.failures[0].includes('2 hosts'));
}


// The generated file supplies fields, while the plugin still selects its own contribution set.
{
  const metadata = {
    'core.a': { id: 'core.a', audience: 'DEVELOPER', placement: 'DEEPLINK', mountTag: 'jf-a' },
    'core.java-only': { id: 'core.java-only', audience: 'USER', placement: 'RAIL', mountTag: 'jf-only' },
  };
  const generatedSource = `export const CORE_SURFACE_METADATA = ${JSON.stringify(metadata)} as const;`;
  const pluginSource = "const CORE_SURFACES = [{ ...CORE_SURFACE_METADATA['core.a'],\n  },];";
  const fe = parseCorePluginSurfaces(pluginSource, generatedSource);
  ok('projection resolves selected metadata without contributing Java-only surfaces',
    fe.size === 1 && fe.get('core.a').audience === 'DEVELOPER' && fe.get('core.a').placement === 'DEEPLINK');
  const expanded = expandCoreSurfaceMetadata('import { CORE_SURFACE_METADATA } from "generated";\n' + pluginSource, generatedSource);
  ok('expanded declarations can pass through the pure parser again', parseCorePluginSurfaces(expanded).size === 1);
  const javaSource = javaCatalog(AGREE_JAVA);
  const good = run({ javaSource, pluginSource, generatedSource, paths: PATHS });
  ok('generated declarations still enter the parity leg', good.compared === 1 && good.failures.length === 0);
  const bad = run({ javaSource, pluginSource,
    generatedSource: generatedSource.replace('DEVELOPER', 'USER'), paths: PATHS });
  ok('a changed generated audience still fails parity', bad.failures.some((f) => f.includes('audience')));
  const overridden = run({ javaSource,
    pluginSource: pluginSource.replace("['core.a'],", "['core.a'], audience: 'USER',"),
    generatedSource, paths: PATHS });
  ok('a local override cannot hide behind the generated audience', overridden.failures.length === 1);
  for (const [label, source, generated] of [
    ['missing projection', pluginSource, undefined],
    ['missing metadata entry', pluginSource.replace('core.a', 'core.missing'), generatedSource],
    ['moved reference', pluginSource.replace('...CORE_SURFACE_METADATA[', '...OTHER_METADATA[') +
      ' import { CORE_SURFACE_METADATA } from "generated";', generatedSource],
  ]) {
    let rejected = false;
    try { parseCorePluginSurfaces(source, generated); } catch { rejected = true; }
    ok(`${label} fails instead of silently reducing coverage`, rejected);
  }
}

if (failures.length > 0) {
  console.error(`check-surface-composition.test: FAIL (${failures.length} of ${passed + failures.length})`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exitCode = 1;
} else {
  console.log(`check-surface-composition.test: OK (${passed} assertions)`);
}
