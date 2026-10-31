/**
 * Tests for the surface-composition gate (scripts/ci/check-surface-composition.mjs).
 *
 * Exercises the one-home composition rules and member resolution across Java and frontend
 * declarations, including frontend contributions selected from generated metadata.
 *
 * Run: `node scripts/ci/check-surface-composition.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { expandCoreSurfaceMetadata } from '../lib/core-surface-declarations.mjs';

import { run, parseJavaSurfaces, parseCorePluginIds } from './check-surface-composition.mjs';

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

// --- parsers ---------------------------------------------------------------
{
  const surfaces = parseJavaSurfaces(javaCatalog([
    { constant: 'A_SURFACE_ID', id: 'core.a', placement: 'DEEPLINK' },
  ]));
  ok('a Java declaration yields its id and placement',
    surfaces.length === 1 && surfaces[0].id === 'core.a' && surfaces[0].placement === 'DEEPLINK');
}
{
  const source = corePlugin([{ id: 'core.a', audience: 'USER', placement: 'RAIL' }]);
  const ids = parseCorePluginIds(source + "\n// { id: 'core.line-comment' },\n/* { id: 'core.block-comment' }, */");
  ok('commented-out frontend registrations cannot resolve members', ids.size === 1 && ids.has('core.a'));
}

// --- composition -----------------------------------------------------------
{
  const javaSource = javaCatalog([
    { constant: 'HOST_SURFACE_ID', id: 'core.host', placement: 'RAIL', members: 'MEMBER_SURFACE_ID' },
    { constant: 'MEMBER_SURFACE_ID', id: 'core.member', placement: 'DEEPLINK' },
  ]);
  const r = run({ javaSource, pluginSource: null });
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
  const r = run({ javaSource, pluginSource: null });
  ok('a member that is ALSO a RAIL surface fails (two homes)', r.failures.length === 1 && r.failures[0].includes('one home'));
}
{
  const javaSource = javaCatalog([
    { constant: 'HOST_SURFACE_ID', id: 'core.host', placement: 'RAIL', members: 'HOST_SURFACE_ID' },
  ]);
  const r = run({ javaSource, pluginSource: null });
  ok('a surface hosting itself fails', r.failures.some((f) => f.includes('cannot host itself')));
}
{
  const javaSource = javaCatalog([
    { constant: 'HOST_SURFACE_ID', id: 'core.host', placement: 'RAIL', members: 'new SurfaceRef("core.nowhere")' },
  ]);
  const r = run({ javaSource, pluginSource: null });
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
  });
  ok('a CorePlugin-contributed member resolves against the merged id set', r.failures.length === 0);
}
{
  const javaSource = javaCatalog([
    { constant: 'HOST_A_SURFACE_ID', id: 'core.host-a', placement: 'RAIL', members: 'MEMBER_SURFACE_ID' },
    { constant: 'HOST_B_SURFACE_ID', id: 'core.host-b', placement: 'RAIL', members: 'MEMBER_SURFACE_ID' },
    { constant: 'MEMBER_SURFACE_ID', id: 'core.member', placement: 'DEEPLINK' },
  ]);
  const r = run({ javaSource, pluginSource: null });
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
  const fe = parseCorePluginIds(pluginSource, generatedSource);
  ok('projection resolves selected metadata without contributing Java-only surfaces',
    fe.size === 1 && fe.has('core.a'));
  const expanded = expandCoreSurfaceMetadata('import { CORE_SURFACE_METADATA } from "generated";\n' + pluginSource, generatedSource);
  ok('expanded declarations can pass through the pure parser again', parseCorePluginIds(expanded).size === 1);
  const javaSource = javaCatalog([
    { constant: 'HOST_SURFACE_ID', id: 'core.host', members: 'new SurfaceRef("core.a")' },
  ]);
  const good = run({ javaSource, pluginSource, generatedSource });
  ok('a selected generated contribution resolves as a member', good.failures.length === 0);
  const missing = run({ javaSource,
    pluginSource: pluginSource.replace('core.a', 'core.java-only'), generatedSource });
  ok('unselected generated metadata cannot resolve a dangling member',
    missing.failures.length === 1 && missing.failures[0].includes('dangling'));
  for (const [label, source, generated] of [
    ['missing projection', pluginSource, undefined],
    ['missing metadata entry', pluginSource.replace('core.a', 'core.missing'), generatedSource],
    ['moved reference', pluginSource.replace('...CORE_SURFACE_METADATA[', '...OTHER_METADATA[') +
      ' import { CORE_SURFACE_METADATA } from "generated";', generatedSource],
  ]) {
    let rejected = false;
    try { parseCorePluginIds(source, generated); } catch { rejected = true; }
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
