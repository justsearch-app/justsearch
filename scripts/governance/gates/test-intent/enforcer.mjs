/**
 * test-intent enforcer — discipline-gate kernel (tempdoc 966 D1).
 *
 * Every expected outcome that enters, changes or leaves the product test suite names a source of
 * intent and carries a non-author acceptance record bound to the exact flagged content. The
 * analysis lives in analyze.mjs (shared with the local CLI, cli.mjs); this file adapts it to the
 * kernel's enforcer contract and prints the agent-facing report, because the runner itself prints
 * only a per-gate verdict line.
 *
 * Base resolution is the gate's own (event-aware: pull_request, merge_group, push, local), so the
 * registry entry declares no `baseline` and the runner passes `baselineRef: null`.
 *
 * Self-test (fixture mode): `<fixtureRoot>/scenario.json` is a scenario (scenario.mjs) that is
 * built as a real scratch git repo and analysed end to end.
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { analyzeTestIntent, formatReport } from './analyze.mjs';
import { TEST_INTENT_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import { runScenario } from './scenario.mjs';
import { writeStepSummary } from './weekly-report.mjs';

const TOOL = { toolName: 'justsearch-test-intent', toolVersion: '0.1.0' };

export async function enforceTestIntent(options) {
  const { repoRoot, fixtureMode = false, fixtureRoot } = options;
  if (fixtureMode && fixtureRoot) {
    const scenario = JSON.parse(readFileSync(resolve(fixtureRoot, 'scenario.json'), 'utf8'));
    const { result, cleanup } = runScenario(scenario);
    cleanup();
    return { ...TOOL, findings: result.findings, verdict: result.verdict, ruleDescriptions: TEST_INTENT_RULE_DESCRIPTIONS };
  }
  const result = analyzeTestIntent({ repoRoot, env: process.env });
  console.log(formatReport(result));
  // Tempdoc 966 Measurement: the rolling four-week counts go to the job summary on every CI run
  // (no scheduled workflow; ADR-0026). Advisory and never fatal, so the verdict is unaffected.
  writeStepSummary({ repoRoot, env: process.env });
  return { ...TOOL, findings: result.findings, verdict: result.verdict, ruleDescriptions: TEST_INTENT_RULE_DESCRIPTIONS };
}
