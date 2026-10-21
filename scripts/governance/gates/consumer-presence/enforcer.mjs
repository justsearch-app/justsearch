// consumer-presence gate (tempdoc 560 §5/§6) — the NonEmpty<ConsumerHook> keystone as a build gate.
//
// Reads the in-JVM registry snapshot (governance gates have no JVM; the real, merged consumer truth
// is computed by RegistrySnapshotExporter and handed across as a JSON artifact) and fails any
// declaration with zero consumers, unless grandfathered in exemptions.json.
// This makes "declare an Operation/Resource/Prompt that nothing consumes" fail the build — the cure
// for the substrate-without-consumer defect class (C-018) at the prevention-ladder Gate rung.

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { CONSUMER_PRESENCE_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import { verdictForEntry } from './truth-table.mjs';

const TOOL_NAME = 'justsearch-consumer-presence';
const TOOL_VERSION = '0.1.0';
const DEFAULT_SNAPSHOT = 'tmp/consumer-presence/registry-snapshot.json';
const DEFAULT_EXEMPTIONS = 'gates/consumer-presence/exemptions.json';

export async function enforceConsumerPresence(options) {
  const { repoRoot, gate, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const snapshotRel = gate.config?.snapshotPath ?? DEFAULT_SNAPSHOT;
  const snapshotPath = resolve(sourceRoot, snapshotRel);
  const exemptionsPath = resolve(sourceRoot, gate.baseline?.path ?? DEFAULT_EXEMPTIONS);

  const findings = [];
  const result = (verdict) => ({
    toolName: TOOL_NAME,
    toolVersion: TOOL_VERSION,
    findings,
    verdict,
    ruleDescriptions: CONSUMER_PRESENCE_RULE_DESCRIPTIONS,
  });

  if (!existsSync(snapshotPath)) {
    findings.push({
      ruleId: 'consumer-presence/snapshot-missing',
      level: 'warning',
      message:
        `registry snapshot not found at ${snapshotRel}. Run ./gradlew.bat ` +
        `:modules:app-services:test (RegistrySnapshotExporterTest) to generate it.`,
      uri: snapshotRel,
    });
    return result('pass'); // soft-fail: missing artifact does not block
  }

  let snapshot;
  try {
    snapshot = JSON.parse(readFileSync(snapshotPath, 'utf8'));
  } catch (e) {
    findings.push({
      ruleId: 'consumer-presence/snapshot-unreadable',
      level: 'warning',
      message: `registry snapshot at ${snapshotRel} could not be parsed: ${e.message}`,
      uri: snapshotRel,
    });
    return result('pass');
  }

  const exemptions = loadExemptions(exemptionsPath);
  const entries = Array.isArray(snapshot.entries) ? snapshot.entries : [];
  let anyFail = false;

  for (const entry of entries) {
    const isExempt = exemptions.has(entry.id);
    const status = verdictForEntry(entry, isExempt);
    if (status === 'fail') {
      anyFail = true;
      findings.push({
        ruleId: 'consumer-presence/no-consumers',
        level: 'error',
        message:
          `${entry.kind} '${entry.id}' has 0 consumers — substrate-without-a-consumer is forbidden ` +
          `(tempdoc 560 §5/§6, P1). Give it an executor, a consuming surface, or an inline ` +
          `ConsumerHook; or grandfather it in ${DEFAULT_EXEMPTIONS} with justification.`,
        uri: snapshotRel,
      });
    } else if (status === 'info') {
      findings.push({
        ruleId: 'consumer-presence/exempt-orphan',
        level: 'note',
        message: `${entry.kind} '${entry.id}' has 0 consumers but is grandfathered in exemptions.json.`,
        uri: snapshotRel,
      });
    }
  }

  // Stale-exemption hygiene: shrink the baseline as declarations gain consumers or disappear.
  const orphanIds = new Set(
    entries.filter((e) => (typeof e.consumerCount === 'number' ? e.consumerCount : 0) === 0).map((e) => e.id),
  );
  for (const id of exemptions) {
    if (!orphanIds.has(id)) {
      findings.push({
        ruleId: 'consumer-presence/stale-exemption',
        level: 'warning',
        message: `exemption '${id}' is no longer an orphaned declaration — remove it from exemptions.json.`,
        uri: gate.baseline?.path ?? DEFAULT_EXEMPTIONS,
      });
    }
  }

  return result(anyFail ? 'fail' : 'pass');
}

function loadExemptions(path) {
  if (!existsSync(path)) return new Set();
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8'));
    const list = Array.isArray(parsed.exemptions) ? parsed.exemptions : [];
    return new Set(list.map((e) => (typeof e === 'string' ? e : e?.id)).filter(Boolean));
  } catch {
    return new Set();
  }
}
