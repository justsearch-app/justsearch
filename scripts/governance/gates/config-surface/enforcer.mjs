/**
 * config-surface enforcer — tempdoc 799 K.4.
 *
 * Ratchets the runtime **configuration surface** so a cleanup stays cleaned up.
 * Tempdoc 754 classified 70 knobs and deleted 31, then recorded its own gap:
 * "no regrowth gate". Without one the surface returns to its prior size and the
 * next campaign pays the same cost — so this gate is the durable half of that work.
 *
 * WHAT IT COUNTS (declared deliberately — see tempdoc 799 §D.1): the metrics
 * emitted by `scripts/docs/generate-runtime-config-matrix.mjs`, which reads the
 * three configuration authorities (EnvRegistry, ConfigKey, ResolvedConfigBuilder)
 * and reports `yamlKeyCount`, `envSyspropPairCount` and `configKeyCount`.
 *
 * HONEST LIMIT: at the time this was written, configuration reached the Worker by three
 * parallel paths (the worker-config snapshot, blanket JUSTSEARCH_* env forwarding, and the
 * explicit WorkerSpawner forwarded-props list), and the post-handshake divergence check only
 * WARNed. Item A11 has since deleted WorkerSpawner along with the Worker child process, so that
 * third path no longer exists as described here; whether an equivalent undeclared path exists
 * for the current, merged process has not been verified. A count over the declared authorities
 * can therefore read complete while an undeclared path grows. This gate ratchets what is
 * *declared*; it does not claim to see every effective knob.
 *
 * Baseline format (TSV, per line): `<metric> <count> <date>` — merge-friendly,
 * mirroring gates/module-deps/baseline.txt.
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  CONFIG_SURFACE_CLASSIFICATIONS,
  aggregateConfigSurfaceClassifications,
} from './classifications.mjs';
import { CONFIG_SURFACE_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';

import { scanDeadConfig } from './dead-config.mjs';
import { scanYamlReaders } from './yaml-readers.mjs';
import { validateConfigLifecycle } from './lifecycle.mjs';
import {
  verdictForMetric,
  verdictForBaselineShift,
  verdictForDeadKey,
  verdictForUnreadComponent,
  verdictForUnreadYamlKey,
  verdictForSysaccessGrowth,
} from './truth-table.mjs';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readPriorBaselineText } from '../../lib/prior-baseline.mjs';
import { repinFinding, repinRuleDescription } from '../../lib/declared-growth-repin.mjs';

const TOOL = { toolName: 'justsearch-config-surface', toolVersion: '0.2.0' };

const SPLIT_LINES = new RegExp("\\r?\\n");

function deadBaselineUri(gate) {
  return gate.config?.deadConfigBaseline ?? 'gates/config-surface/dead-config-baseline.txt';
}

/** Report field → baseline metric name. */
/** The gate's own vocabulary plus the shared repin rule (tempdoc 918). */
const RULE_DESCRIPTIONS = { ...CONFIG_SURFACE_RULE_DESCRIPTIONS, ...repinRuleDescription('config-surface') };

/** What each metric COUNTS — `yaml_keys` is not "config keys", and the finding says so. */
const METRIC_UNITS = {
  yaml_keys: 'application.yaml keys',
  env_sysprop_pairs: 'env/sysprop pairs',
  config_keys: 'runtime config keys',
};

const METRICS = {
  yaml_keys: 'yamlKeyCount',
  env_sysprop_pairs: 'envSyspropPairCount',
  config_keys: 'configKeyCount',
};

function parseBaseline(content) {
  const map = new Map();
  for (const raw of (content ?? '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    if (parts.length < 2) continue;
    const count = Number(parts[1]);
    if (Number.isFinite(count)) map.set(parts[0], count);
  }
  return map;
}

export async function enforceConfigSurface(options) {
  const { repoRoot, gate, baselineRef, rebalance = false, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const reportRel =
    gate.config?.reportPath ??
    'tmp/agent-evidence/_summaries/runtime-config-ownership-matrix.generated.json';
  const reportPath = resolve(sourceRoot, reportRel);

  const baseline = existsSync(baselinePath)
    ? parseBaseline(readFileSync(baselinePath, 'utf8'))
    : new Map();

  const findings = [];
  let verdict = 'pass';

  let report;
  try {
    report = JSON.parse(readFileSync(reportPath, 'utf8'));
  } catch {
    return {
      ...TOOL,
      findings: [
        {
          ruleId: 'config-surface/report-malformed',
          level: 'error',
          message: `malformed or unreadable runtime-config matrix report at ${reportRel}`,
          uri: reportRel,
        },
      ],
      verdict: 'fail',
      ruleDescriptions: RULE_DESCRIPTIONS,
    };
  }

  if (!fixtureMode) {
    const lifecycleRel =
      gate.config?.configLifecycle ?? 'governance/config-lifecycle.v1.json';
    let lifecycleOverlay;
    try {
      lifecycleOverlay = JSON.parse(readFileSync(resolve(sourceRoot, lifecycleRel), 'utf8'));
    } catch {
      lifecycleOverlay = null;
    }
    const lifecycleFindings = validateConfigLifecycle({
      matrix: report,
      overlay: lifecycleOverlay,
      repoRoot: sourceRoot,
    });
    if (lifecycleFindings.length > 0) {
      verdict = 'fail';
      findings.push(...lifecycleFindings.map((finding) => ({ ...finding, uri: lifecycleRel })));
    }
  }

  const declarations = gate.changesetsDir
    ? loadChangesets({
        repoRoot: sourceRoot,
        changesetsDir: gate.changesetsDir,
        baselineRef,
        allowedClassifications: CONFIG_SURFACE_CLASSIFICATIONS,
        classificationField: 'classification',
        requireJustificationFor: new Set(['declared-growth', 'merge-import', 'emergency-override']),
        fixtureMode,
      })
    : [];
  const aggregated = aggregateConfigSurfaceClassifications(declarations);
  const coveringClassification = !aggregated.growthCovered
    ? 'silent-growth'
    : aggregated.classifications.find((c) =>
        ['declared-growth', 'merge-import', 'emergency-override'].includes(c),
      ) ?? 'silent-growth';

  const rebalanceWrites = new Map();

  // Tempdoc 918: the repin rule needs the pin as it stood at the PR base.
  const priorMetricText = readPriorBaselineText({
    fixtureMode, fixtureRoot, sourceRoot, baselineRef, baselinePath: gate.baseline.path,
  });
  const priorMetricBaseline = priorMetricText === null ? null : parseBaseline(priorMetricText);

  for (const [metric, field] of Object.entries(METRICS)) {
    const current = Number(report[field]);
    if (!Number.isFinite(current)) continue;
    const pinned = baseline.get(metric) ?? current;
    const v = verdictForMetric({
      metric,
      current,
      pinned,
      classification: coveringClassification,
    });
    // Tempdoc 918: a covering changeset licenses the pin advance, not an unpinned overflow.
    // `verdictForMetric` returns pass for both "at baseline" and "over baseline but covered";
    // `current > pinned` is what separates them.
    if (v.status === 'pass' && current > pinned) {
      verdict = 'fail';
      findings.push(repinFinding({
        rulePrefix: 'config-surface', classification: coveringClassification, row: metric,
        measured: current, livePin: pinned, priorPin: priorMetricBaseline?.get(metric),
        baselineFile: gate.baseline.path, unit: METRIC_UNITS[metric] ?? 'entries',
        pinLine: `${metric} ${current} <today>`, uri: gate.baseline.path,
      }));
    } else if (v.status === 'fail') {
      verdict = 'fail';
      findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
    } else if (v.status === 'info') {
      findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: gate.baseline.path });
      if (rebalance && current < pinned) rebalanceWrites.set(metric, current);
    }
  }

  // --- Reader-presence (tempdoc 799 §O.3). The count ratchet above answers "how many settings
  // exist"; this answers "does anything read them", which is the defect 754 catalogued and which
  // 799 §N.2.f.1 proved the count ratchet cannot see. Skipped in fixtureMode: the fixtures are
  // synthetic count-ratchet trees with no Java sources, and dead-config.mjs has its own unit test.
  if (!fixtureMode) {
    const deadBaselinePath = resolve(
      sourceRoot,
      gate.config?.deadConfigBaseline ?? 'gates/config-surface/dead-config-baseline.txt',
    );
    const pinned = new Set();
    if (existsSync(deadBaselinePath)) {
      for (const raw of readFileSync(deadBaselinePath, 'utf8').split(SPLIT_LINES)) {
        const line = raw.trim();
        if (!line || line.startsWith('#')) continue;
        pinned.add(line);
      }
    }
    const scan = scanDeadConfig(sourceRoot);
    if (!scan.skipped) {
      for (const key of scan.deadKeys) {
        const v = verdictForDeadKey({ key, baselined: pinned.has('key:' + key) });
        if (v.status === 'fail') {
          verdict = 'fail';
          findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: deadBaselineUri(gate) });
        } else {
          findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: deadBaselineUri(gate) });
        }
      }
      for (const component of scan.unreadComponents) {
        const v = verdictForUnreadComponent({
          component,
          baselined: pinned.has('component:' + component),
        });
        if (v.status === 'fail') {
          verdict = 'fail';
          findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: deadBaselineUri(gate) });
        } else {
          findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: deadBaselineUri(gate) });
        }
      }
    }

    // --- YAML-reader presence (tempdoc 883 decision 5). The scan above starts from what the
    // resolver DECLARES; this one starts from what the shipped YAML OFFERS. A key that exists only
    // in application.yaml is invisible to the other half by construction — the two keys tempdoc 882
    // found by hand (search.pipeline.profile / index.pipeline.profile) were green on every check in
    // the repo, because nothing in scripts/ parsed application.yaml at all.
    const yamlScan = scanYamlReaders(
      sourceRoot,
      gate.config?.applicationYaml ?? 'config/application.yaml',
    );
    if (yamlScan.parseError) {
      verdict = 'fail';
      findings.push({
        ruleId: 'config-surface/yaml-parse-failed',
        level: 'error',
        message: `config/application.yaml did not parse: ${yamlScan.parseError}`,
        uri: gate.config?.applicationYaml ?? 'config/application.yaml',
      });
    } else if (!yamlScan.skipped) {
      for (const key of yamlScan.unreadYamlKeys) {
        const v = verdictForUnreadYamlKey({ key, baselined: pinned.has('yaml:' + key) });
        if (v.status === 'fail') {
          verdict = 'fail';
          findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: deadBaselineUri(gate) });
        } else {
          findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: deadBaselineUri(gate) });
        }
      }
    }
  }

  // --- System-access allowlist ratchet (tempdoc 883 decision 5). SystemAccessFunnelTest fails on a
  // call site missing from the list; this fails on the list GROWING, which is the one-line way to
  // make that test green without routing the value through the resolver. Same shape as the metric
  // ratchet above: it only shrinks, and growth needs a declared changeset.
  const sysaccessRel = gate.config?.sysaccessAllowlist ?? 'gates/config-surface/sysaccess-allowlist.txt';
  const sysaccessPath = resolve(sourceRoot, sysaccessRel);
  if (existsSync(sysaccessPath)) {
    const priorRaw = readPriorBaselineText({
      fixtureMode, fixtureRoot, sourceRoot, baselineRef, baselinePath: sysaccessRel,
    });
    if (priorRaw !== null && priorRaw !== undefined) {
      const parseEntries = (content) =>
        new Set(
          (content ?? '')
            .split(SPLIT_LINES)
            .map((l) => l.trim())
            .filter((l) => l && !l.startsWith('#')),
        );
      const live = parseEntries(readFileSync(sysaccessPath, 'utf8'));
      const prior = parseEntries(priorRaw);
      const added = [...live].filter((e) => !prior.has(e)).sort();
      const v = verdictForSysaccessGrowth({ added, classification: coveringClassification });
      if (v.status === 'fail') {
        verdict = 'fail';
        findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: sysaccessRel });
      } else if (v.status === 'info') {
        findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: sysaccessRel });
      }
    }
  }

  // Baseline-shift detection — catches relaxing the pin instead of the surface.
  const priorBaselineText = readPriorBaselineText({
    fixtureMode, fixtureRoot, sourceRoot, baselineRef, baselinePath: gate.baseline.path,
  });
  if (priorBaselineText !== null) {
    const priorBaseline = parseBaseline(priorBaselineText);
    for (const [metric, livePin] of baseline.entries()) {
      const priorPin = priorBaseline.get(metric);
      if (priorPin === undefined) continue;
      const v = verdictForBaselineShift({
        metric,
        priorPin,
        livePin,
        classification: coveringClassification,
      });
      if (v.status === 'fail') {
        verdict = 'fail';
        findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
      }
    }
  }

  if (rebalance && rebalanceWrites.size > 0) {
    const date = new Date().toISOString().slice(0, 10);
    const lines = [
      '# config-surface ratchet — tempdoc 799 K.4. <metric> <count> <date>',
      '# Produced from scripts/docs/generate-runtime-config-matrix.mjs. Only ratchets DOWN.',
    ];
    for (const metric of Object.keys(METRICS)) {
      const pinned = rebalanceWrites.has(metric) ? rebalanceWrites.get(metric) : baseline.get(metric);
      if (Number.isFinite(pinned)) lines.push(`${metric} ${pinned} ${date}`);
    }
    writeFileSync(baselinePath, lines.join('\n') + '\n');
  }

  return {
    ...TOOL,
    findings,
    verdict,
    ruleDescriptions: RULE_DESCRIPTIONS,
    rebalanceWrites: [...rebalanceWrites.entries()].map(([metric, c]) => ({
      file: gate.baseline.path,
      before: String(baseline.get(metric) ?? ''),
      after: String(c),
    })),
  };
}
