// engine-port gate (tempdoc 936, lane F design section 3.3 — "Ports: an open, catalogued set").
//
// Design 3.3: "The engine API is a set of ports, each an interface in a contract module (core or
// app-api), catalogued with its owner and consumers, and ArchUnit-pinned so only the composition
// root binds an implementation. Adding a port is a catalogue entry, an interface and a
// composition-root binding. Nothing else in the repo may construct an implementation of a port."
//
// Rule 6b (LayeringEnforcementTest / ADR-0049) is the outer pin: it stops application code
// reaching PAST a port into Lucene. This gate is the inner one: it stops a second implementation
// of a port appearing without being declared. They answer different questions, which is why both
// exist — 6b guards the boundary, this guards the set of things allowed to speak for it.
//
// Direction matters. The check that earns the gate its keep is source -> register (a new binding
// must be declared), not register -> source: the drift a collapsed process introduces is a NEW
// implementation appearing quietly, not a declared one going missing.
//
// All judgement lives in truth-table.mjs; this file only measures.

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';

import { ENGINE_PORT_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForDeclaredImplementation,
  verdictForInterfacePresence,
  verdictForRegisterReadable,
  verdictForScanPopulation,
  verdictForScannedImplementation,
} from './truth-table.mjs';

const TOOL_NAME = 'justsearch-engine-port';
const TOOL_VERSION = '0.1.0';
const DEFAULT_REGISTER = 'governance/engine-ports.v1.json';

/** Recursively collects .java files under `dir` whose path contains `include`. */
export function collectJavaFiles(dir, include, out = []) {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      // `build` holds generated and copied sources: scanning it double-counts every class and
      // reports paths nobody can edit.
      if (entry.name === 'build' || entry.name === 'node_modules' || entry.name === '.git') continue;
      collectJavaFiles(full, include, out);
    } else if (entry.isFile() && entry.name.endsWith('.java') && full.includes(include)) {
      out.push(full);
    }
  }
  return out;
}

/**
 * Whether `source` declares a class implementing `simpleName`.
 *
 * A source match, not a bytecode one: this gate runs without a JVM, and the property is about what
 * someone WROTE. The cost is that an indirect implementation (a class extending an abstract class
 * that implements the port) is invisible — which is why the abstract facade is itself a catalogue
 * entry, so its subclasses inherit a declared binding rather than an unnoticed one.
 */
export function declaresImplements(source, simpleName) {
  const withoutComments = source.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^\s*\/\/.*$/gm, ' ');
  for (const clause of withoutComments.matchAll(/\bimplements\b([^{;]*)\{/g)) {
    const names = clause[1].split(',').map((n) => n.trim().replace(/<.*$/, ''));
    if (names.some((n) => n === simpleName || n.endsWith('.' + simpleName))) return true;
  }
  return false;
}

/**
 * Whether `source` declares a class extending `simpleName`. The subclass half of a binding: a
 * class can reach a port without ever naming it, by extending something that does.
 */
export function declaresExtends(source, simpleName) {
  const withoutComments = source.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^\s*\/\/.*$/gm, ' ');
  for (const clause of withoutComments.matchAll(/\bextends\b([^{;]*)\{/g)) {
    // Strip a trailing `implements ...` clause. The capture is greedy up to the brace, so
    // `class X extends A implements B {` would otherwise yield the single name "A implements B"
    // and match nothing. The self-test's second declaresExtends case is exactly that, and it
    // failed on the first run — which is the only reason this line exists.
    const names = clause[1]
      .split(/\bimplements\b/)[0]
      .split(',')
      .map((n) => n.trim().replace(/<.*$/, ''));
    if (names.some((n) => n === simpleName || n.endsWith('.' + simpleName))) return true;
  }
  return false;
}

export async function enforceEnginePort(options) {
  const { repoRoot, gate, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const registerRel = gate.config?.register ?? DEFAULT_REGISTER;
  const registerPath = resolve(sourceRoot, registerRel);

  const findings = [];
  let verdict = 'pass';
  const record = (v, uri) => {
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri });
    } else if (v.status === 'info') {
      findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri });
    }
  };
  const result = () => ({
    toolName: TOOL_NAME,
    toolVersion: TOOL_VERSION,
    findings,
    verdict,
    ruleDescriptions: ENGINE_PORT_RULE_DESCRIPTIONS,
  });

  let register;
  try {
    register = JSON.parse(readFileSync(registerPath, 'utf8'));
  } catch (e) {
    record(verdictForRegisterReadable({ message: e.message, path: registerRel }), registerRel);
    return result();
  }

  const ports = Array.isArray(register.ports) ? register.ports : [];
  const interfacePorts = ports.filter((p) => p.kind === 'interface');

  // --- 1. every registered interface exists and declares itself ------------------------------
  const bySimpleName = new Map();
  for (const port of interfacePorts) {
    const rel = port.interfaceFile;
    const abs = rel ? resolve(sourceRoot, rel) : null;
    const simpleName = String(port.interface ?? '').split('.').pop();
    const fileExists = Boolean(abs && existsSync(abs));
    const declaresInterface =
      fileExists && new RegExp(`\\binterface\\s+${simpleName}\\b`).test(readFileSync(abs, 'utf8'));
    const v = verdictForInterfacePresence({
      portId: port.id,
      interfaceFile: rel,
      simpleName,
      fileExists,
      declaresInterface,
    });
    record(v, rel ?? registerRel);
    if (v.status === 'pass') bySimpleName.set(simpleName, port);
  }

  // --- 2. every declared implementation exists and still implements ---------------------------
  const declared = new Set();
  for (const port of interfacePorts) {
    const simpleName = String(port.interface ?? '').split('.').pop();
    for (const impl of port.implementations ?? []) {
      const abs = impl.file ? resolve(sourceRoot, impl.file) : null;
      const fileExists = Boolean(abs && existsSync(abs));
      // `via` defaults to "implements"; "extends <Class>" covers a subclass of a declared
      // implementation, which is how the live binding actually reaches the port.
      const via = impl.via ?? 'implements';
      const src = fileExists ? readFileSync(abs, 'utf8') : '';
      const bindsPort = fileExists && (via === 'implements'
        ? declaresImplements(src, simpleName)
        : declaresExtends(src, via.slice('extends '.length).trim()));
      record(
        verdictForDeclaredImplementation({
          portId: port.id,
          className: impl.class,
          file: impl.file,
          simpleName,
          via,
          fileExists,
          bindsPort,
        }),
        impl.file ?? registerRel,
      );
      if (fileExists) declared.add(impl.file);
    }
  }

  // --- 3. source -> register: no undeclared implementation ------------------------------------
  const scan = register.scan ?? {};
  const roots = Array.isArray(scan.javaMainRoots) ? scan.javaMainRoots : ['modules'];
  const include = (scan.javaInclude ?? '/src/main/java/').split('/').join(sep);
  const files = [];
  for (const root of roots) collectJavaFiles(resolve(sourceRoot, root), include, files);

  let found = 0;
  for (const abs of files) {
    const src = readFileSync(abs, 'utf8');
    for (const [simpleName, port] of bySimpleName) {
      if (!declaresImplements(src, simpleName)) continue;
      found += 1;
      const rel = relative(sourceRoot, abs).split(sep).join('/');
      record(
        verdictForScannedImplementation({
          portId: port.id,
          simpleName,
          path: rel,
          declared: declared.has(rel),
        }),
        rel,
      );
    }
  }

  // --- 4. vacuous-pass guard -------------------------------------------------------------------
  record(
    verdictForScanPopulation({ found, floor: Number(scan.expectedMinImplementations ?? 0) }),
    registerRel,
  );

  return result();
}

export default enforceEnginePort;
