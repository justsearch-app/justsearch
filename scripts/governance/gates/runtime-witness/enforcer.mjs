// runtime-witness gate (tempdoc 560 §5) — the SECOND half of the NonEmpty<ConsumerHook> keystone.
//
// The consumer-presence gate is a STATIC referencer scan: it proves a declaration NAMES a consumer.
// It cannot prove the consumer actually RECEIVES the declaration's data. This gate closes that half
// for the AGENT audience (AGENT = prompt-construction): it cross-checks the declared agent consumers
// against the witness datum the exporter computed by running the REAL AgentOperationEmitter — the
// operation ids the agent's tool list actually offers (snapshot.witness.agentDelivered).
//
// Bidirectional consistency (audience-dispatched delivery):
//   - OVER-CLAIM: an operation declares an "agent-loop" consumer and is agent-audience-eligible
//     (USER/AGENT) but is NOT in the delivered set — the static gate sees a consumer the
//     prompt-construction channel never carries.
//   - PHANTOM: an operation is delivered (offered to the model) but is NOT a declared,
//     agent-audience-eligible agent consumer — the channel carries something the declarations
//     do not account for.
//
// OPERATOR-audience ops that merely carry ExecutorTag.AGENT (e.g. bulk-reindex) are
// execution-consumed by the agent loop but correctly NOT offered; they are excluded from both
// sides, so they are neither over-claim nor phantom — the truthful result.

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { RUNTIME_WITNESS_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import { verdictForWitness } from './truth-table.mjs';

const TOOL_NAME = 'justsearch-runtime-witness';
const TOOL_VERSION = '0.1.0';
const DEFAULT_SNAPSHOT = 'tmp/consumer-presence/registry-snapshot.json';
const AGENT_AUDIENCES = new Set(['USER', 'AGENT']);

export async function enforceRuntimeWitness(options) {
  const { repoRoot, gate, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const snapshotRel = gate.config?.snapshotPath ?? DEFAULT_SNAPSHOT;
  const snapshotPath = resolve(sourceRoot, snapshotRel);

  const findings = [];
  const result = (verdict) => ({
    toolName: TOOL_NAME,
    toolVersion: TOOL_VERSION,
    findings,
    verdict,
    ruleDescriptions: RUNTIME_WITNESS_RULE_DESCRIPTIONS,
  });

  if (!existsSync(snapshotPath)) {
    findings.push({
      ruleId: 'runtime-witness/snapshot-missing',
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
      ruleId: 'runtime-witness/snapshot-unreadable',
      level: 'warning',
      message: `registry snapshot at ${snapshotRel} could not be parsed: ${e.message}`,
      uri: snapshotRel,
    });
    return result('pass');
  }

  const witness = snapshot.witness;
  if (!witness || !Array.isArray(witness.agentDelivered)) {
    findings.push({
      ruleId: 'runtime-witness/witness-absent',
      level: 'warning',
      message:
        `snapshot at ${snapshotRel} has no witness.agentDelivered block — it predates the §5 ` +
        `runtime-witness exporter. Regenerate via RegistrySnapshotExporterTest.`,
      uri: snapshotRel,
    });
    return result('pass');
  }

  const delivered = new Set(witness.agentDelivered);
  const entries = Array.isArray(snapshot.entries) ? snapshot.entries : [];

  let anyFail = false;

  // ── Catalog-projected coverage (tempdoc 560 §5; the 548 §5.2 / execution-surface pattern) ──
  // The witness's coverage universe is the declaration-kinds register, NOT a hardcoded list — so a
  // NEW declaration kind cannot escape the keystone. A snapshot kind absent from the register fails
  // (it was never declared a witness channel); a register kind marked POPULATED that produced no
  // snapshot entries fails (the catalog wiring or the exporter dropped it).
  const registerRel = gate.config?.declarationKindsPath ?? 'governance/declaration-kinds.v1.json';
  const registerPath = resolve(sourceRoot, registerRel);
  if (existsSync(registerPath)) {
    let register;
    try {
      register = JSON.parse(readFileSync(registerPath, 'utf8'));
    } catch (e) {
      findings.push({
        ruleId: 'runtime-witness/register-unreadable',
        level: 'warning',
        message: `declaration-kinds register at ${registerRel} could not be parsed: ${e.message}`,
        uri: registerRel,
      });
      register = null;
    }
    if (register && Array.isArray(register.kinds)) {
      const registerKinds = new Map(register.kinds.map((k) => [k.kind, k]));
      const snapshotKinds = new Set(entries.map((e) => e.kind));
      for (const kind of snapshotKinds) {
        if (!registerKinds.has(kind)) {
          anyFail = true;
          findings.push({
            ruleId: 'runtime-witness/unregistered-kind',
            level: 'error',
            message:
              `declaration kind '${kind}' appears in the registry snapshot but is NOT in ${registerRel} — ` +
              `add it to the declaration-kinds register declaring its witness channel, so the keystone's ` +
              `coverage projects from the catalog and the kind cannot silently escape (tempdoc 560 §5).`,
            uri: registerRel,
          });
        }
      }
      for (const [kind, decl] of registerKinds) {
        if (decl.staticCatalog === 'POPULATED') {
          const count = entries.filter((e) => e.kind === kind).length;
          if (count === 0) {
            anyFail = true;
            findings.push({
              ruleId: 'runtime-witness/missing-populated-kind',
              level: 'error',
              message:
                `kind '${kind}' is declared staticCatalog=POPULATED in ${registerRel} but produced ZERO ` +
                `snapshot entries — the catalog wiring or RegistrySnapshotExporter dropped it (tempdoc 560 §5).`,
              uri: registerRel,
            });
          }
        }
      }
    }
  } else {
    findings.push({
      ruleId: 'runtime-witness/register-missing',
      level: 'warning',
      message: `declaration-kinds register not found at ${registerRel}; coverage projection skipped.`,
      uri: registerRel,
    });
  }

  const declaredAgentEligible = new Set(
    entries
      .filter((e) => e.kind === 'operation')
      .filter((e) => Array.isArray(e.consumers) && e.consumers.includes('agent-loop'))
      .filter((e) => AGENT_AUDIENCES.has(e.audience))
      .map((e) => e.id),
  );

  // Every operation that is either declared-agent-eligible or delivered is a measurement; the
  // verdict function decides pass/fail purely from (declaredEligible, delivered).
  const measuredIds = new Set([...declaredAgentEligible, ...delivered]);
  for (const id of measuredIds) {
    const verdict = verdictForWitness({
      declaredEligible: declaredAgentEligible.has(id),
      delivered: delivered.has(id),
    });
    if (verdict === 'fail') {
      anyFail = true;
    }
  }

  for (const id of declaredAgentEligible) {
    if (!delivered.has(id)) {
      findings.push({
        ruleId: 'runtime-witness/over-claimed-agent-consumer',
        level: 'error',
        message:
          `operation '${id}' declares an 'agent-loop' consumer and is agent-audience-eligible, but ` +
          `the AgentOperationEmitter does NOT offer it to the model — the declared consumer never ` +
          `receives the data (tempdoc 560 §5, AGENT = prompt-construction). Fix the declaration's ` +
          `audience/availability, or the emitter selection, so declared ⟺ delivered.`,
        uri: snapshotRel,
      });
    }
  }

  for (const id of delivered) {
    if (!declaredAgentEligible.has(id)) {
      findings.push({
        ruleId: 'runtime-witness/phantom-agent-offering',
        level: 'error',
        message:
          `operation '${id}' is offered to the model by the AgentOperationEmitter but is NOT a ` +
          `declared, agent-audience-eligible agent consumer in the snapshot — the prompt-construction ` +
          `channel carries a declaration the static consumer set does not account for (tempdoc 560 §5).`,
        uri: snapshotRel,
      });
    }
  }

  if (!anyFail) {
    findings.push({
      ruleId: 'runtime-witness/consistent',
      level: 'note',
      message:
        `runtime-witness consistent: the agent offering channel delivers exactly the ` +
        `${declaredAgentEligible.size} declared agent-consumable, agent-audience-eligible operation(s).`,
      uri: snapshotRel,
    });
  }

  return result(anyFail ? 'fail' : 'pass');
}
