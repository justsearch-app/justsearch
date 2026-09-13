// engine-port gate self-test (tempdoc 936). Run: node scripts/governance/gates/engine-port/enforcer.test.mjs
//
// A gate that has only ever been observed passing is a gate nobody has tested. Each case below
// constructs a fixture tree, runs the real enforcer against it, and asserts the verdict — including
// the three failures the gate exists to produce.

import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

import { enforceEnginePort, declaresExtends, declaresImplements } from './enforcer.mjs';

const GATE = { config: { register: 'governance/engine-ports.v1.json' } };

function fixture(files) {
  const root = mkdtempSync(join(tmpdir(), 'engine-port-fixture-'));
  for (const [rel, content] of Object.entries(files)) {
    const abs = join(root, rel);
    mkdirSync(dirname(abs), { recursive: true });
    writeFileSync(abs, content, 'utf8');
  }
  return root;
}

async function run(root) {
  return enforceEnginePort({ repoRoot: root, gate: GATE, fixtureMode: true, fixtureRoot: root });
}

function ruleIds(result) {
  return result.findings.filter((f) => f.level === 'error').map((f) => f.ruleId);
}

const PORT_IFACE = 'package a; public interface SearchPort { }\n';
const IMPL = 'package b; public final class Bound implements SearchPort { }\n';

function register(extra = {}) {
  return JSON.stringify({
    version: 1,
    scan: { javaMainRoots: ['modules'], javaInclude: '/src/main/java/', expectedMinImplementations: 1 },
    ports: [
      {
        id: 'search',
        kind: 'interface',
        interface: 'a.SearchPort',
        interfaceFile: 'modules/core/src/main/java/a/SearchPort.java',
        implementations: [
          { class: 'b.Bound', file: 'modules/app/src/main/java/b/Bound.java' },
        ],
        ...extra,
      },
    ],
  });
}

async function main() {
  // --- unit: the two source predicates --------------------------------------------------------
  assert.ok(declaresImplements('class X implements SearchPort {', 'SearchPort'));
  assert.ok(declaresImplements('class X implements Closeable, a.SearchPort {', 'SearchPort'));
  assert.ok(declaresImplements('class X implements List<SearchPort> , SearchPort {', 'SearchPort'));
  assert.ok(!declaresImplements('// class X implements SearchPort {', 'SearchPort'));
  assert.ok(!declaresImplements('/* class X implements SearchPort { */', 'SearchPort'));
  assert.ok(!declaresImplements('class X extends SearchPort {', 'SearchPort'));
  assert.ok(declaresExtends('class X extends KnowledgeClient {', 'KnowledgeClient'));
  assert.ok(declaresExtends('class X extends KnowledgeClient implements Y {', 'KnowledgeClient'));
  assert.ok(!declaresExtends('class X implements KnowledgeClient {', 'KnowledgeClient'));

  // --- the healthy tree passes ------------------------------------------------------------------
  {
    const root = fixture({
      'governance/engine-ports.v1.json': register(),
      'modules/core/src/main/java/a/SearchPort.java': PORT_IFACE,
      'modules/app/src/main/java/b/Bound.java': IMPL,
    });
    const r = await run(root);
    assert.equal(r.verdict, 'pass', 'a declared, existing binding must pass: ' + JSON.stringify(ruleIds(r)));
    rmSync(root, { recursive: true, force: true });
  }

  // --- THE case the gate exists for: an undeclared implementation --------------------------------
  {
    const root = fixture({
      'governance/engine-ports.v1.json': register(),
      'modules/core/src/main/java/a/SearchPort.java': PORT_IFACE,
      'modules/app/src/main/java/b/Bound.java': IMPL,
      // Somebody in another module quietly binds the port.
      'modules/other/src/main/java/c/Sneaky.java':
        'package c; public final class Sneaky implements SearchPort { }\n',
    });
    const r = await run(root);
    assert.equal(r.verdict, 'fail', 'an undeclared binding must fail the build');
    assert.ok(
      ruleIds(r).includes('engine-port/undeclared-implementation'),
      'and it must fail as undeclared-implementation, not as something else',
    );
    assert.ok(
      r.findings.some((f) => (f.uri ?? '').includes('Sneaky')),
      'the finding must name the offending file, not just the register',
    );
    rmSync(root, { recursive: true, force: true });
  }

  // --- a declared implementation that stopped implementing ---------------------------------------
  {
    const root = fixture({
      'governance/engine-ports.v1.json': register(),
      'modules/core/src/main/java/a/SearchPort.java': PORT_IFACE,
      'modules/app/src/main/java/b/Bound.java': 'package b; public final class Bound { }\n',
    });
    const r = await run(root);
    assert.equal(r.verdict, 'fail');
    assert.ok(ruleIds(r).includes('engine-port/missing-implementation'));
    rmSync(root, { recursive: true, force: true });
  }

  // --- a port whose interface file is gone --------------------------------------------------------
  {
    const root = fixture({
      'governance/engine-ports.v1.json': register(),
      'modules/app/src/main/java/b/Bound.java': IMPL,
    });
    const r = await run(root);
    assert.equal(r.verdict, 'fail');
    assert.ok(ruleIds(r).includes('engine-port/missing-interface'));
    rmSync(root, { recursive: true, force: true });
  }

  // --- the vacuous-scan guard: roots that find nothing ---------------------------------------------
  {
    const root = fixture({
      'governance/engine-ports.v1.json': JSON.stringify({
        version: 1,
        // The renamed-root failure this guard exists for.
        scan: { javaMainRoots: ['renamed'], javaInclude: '/src/main/java/', expectedMinImplementations: 1 },
        ports: [
          {
            id: 'search',
            kind: 'interface',
            interface: 'a.SearchPort',
            interfaceFile: 'modules/core/src/main/java/a/SearchPort.java',
            implementations: [{ class: 'b.Bound', file: 'modules/app/src/main/java/b/Bound.java' }],
          },
        ],
      }),
      'modules/core/src/main/java/a/SearchPort.java': PORT_IFACE,
      'modules/app/src/main/java/b/Bound.java': IMPL,
    });
    const r = await run(root);
    assert.equal(r.verdict, 'fail', 'a scan that finds nothing must fail, not pass vacuously');
    assert.ok(ruleIds(r).includes('engine-port/vacuous-scan'));
    rmSync(root, { recursive: true, force: true });
  }

  // --- `via: extends` is a binding too --------------------------------------------------------------
  {
    const root = fixture({
      'governance/engine-ports.v1.json': register({
        implementations: [
          { class: 'b.Bound', file: 'modules/app/src/main/java/b/Bound.java' },
          {
            class: 'b.Sub',
            file: 'modules/app/src/main/java/b/Sub.java',
            via: 'extends Bound',
          },
        ],
      }),
      'modules/core/src/main/java/a/SearchPort.java': PORT_IFACE,
      'modules/app/src/main/java/b/Bound.java': IMPL,
      'modules/app/src/main/java/b/Sub.java': 'package b; public final class Sub extends Bound { }\n',
    });
    const r = await run(root);
    assert.equal(r.verdict, 'pass', 'a subclass binding must be accepted: ' + JSON.stringify(ruleIds(r)));
    rmSync(root, { recursive: true, force: true });
  }

  // --- ...and a `via: extends` entry that stopped extending must fail ---------------------------------
  {
    const root = fixture({
      'governance/engine-ports.v1.json': register({
        implementations: [
          { class: 'b.Bound', file: 'modules/app/src/main/java/b/Bound.java' },
          { class: 'b.Sub', file: 'modules/app/src/main/java/b/Sub.java', via: 'extends Bound' },
        ],
      }),
      'modules/core/src/main/java/a/SearchPort.java': PORT_IFACE,
      'modules/app/src/main/java/b/Bound.java': IMPL,
      'modules/app/src/main/java/b/Sub.java': 'package b; public final class Sub { }\n',
    });
    const r = await run(root);
    assert.equal(r.verdict, 'fail', 'a `via: extends` claim must be checked, not trusted');
    assert.ok(ruleIds(r).includes('engine-port/missing-implementation'));
    rmSync(root, { recursive: true, force: true });
  }

  console.log('engine-port enforcer self-test: OK (7 cases)');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
