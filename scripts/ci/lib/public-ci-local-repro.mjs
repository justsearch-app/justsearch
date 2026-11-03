import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { LANES } from '../classify-changes.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const DEFAULT_REPRO_PATH = path.resolve(HERE, '..', 'public-ci-local-repro.v1.json');
export const DEFAULT_SIGNAL_POLICY_PATH = path.resolve(HERE, '..', 'workflow-signal-policy.v1.json');

export function requiredCheckContexts(policy) {
  return (Array.isArray(policy?.workflows) ? policy.workflows : [])
    .filter((workflow) => workflow.blocking === true)
    .flatMap((workflow) => Array.isArray(workflow.requiredStatusChecks) ? workflow.requiredStatusChecks : []);
}

export function validatePublicCiLocalRepro({ manifest, signalPolicy }) {
  const errors = [];
  if (manifest?.kind !== 'justsearch-public-ci-local-repro.v1' || manifest?.version !== 1) {
    errors.push('manifest kind/version must be justsearch-public-ci-local-repro.v1 version 1');
  }
  const contexts = Array.isArray(manifest?.contexts) ? manifest.contexts : [];
  const required = requiredCheckContexts(signalPolicy);
  const counts = new Map();
  for (const entry of contexts) {
    counts.set(entry?.check, (counts.get(entry?.check) || 0) + 1);
    if (!entry?.check) errors.push('every context needs a non-empty check name');
    if (entry?.lanes !== undefined && (!Array.isArray(entry.lanes) ||
        !entry.lanes.length || entry.lanes.some(lane => !LANES.includes(lane)))) {
      errors.push(`${entry?.check || '<unnamed>'}: lanes must name known classifier lanes`);
    }
    if (entry?.mode === 'local-subset') {
      if (!Array.isArray(entry.commands) || entry.commands.length === 0 || entry.commands.some((command) => typeof command !== 'string' || !command.trim())) {
        errors.push(`${entry?.check || '<unnamed>'}: local-subset needs non-empty commands`);
      }
    } else if (entry?.mode === 'hosted-only') {
      if (typeof entry.reason !== 'string' || !entry.reason.trim()) errors.push(`${entry?.check || '<unnamed>'}: hosted-only needs a reason`);
      if (entry.commands !== undefined) errors.push(`${entry?.check || '<unnamed>'}: hosted-only must not declare commands`);
    } else {
      errors.push(`${entry?.check || '<unnamed>'}: mode must be local-subset or hosted-only`);
    }
  }
  for (const check of required) {
    if ((counts.get(check) || 0) !== 1) errors.push(`${check}: required check must appear exactly once`);
  }
  for (const check of counts.keys()) {
    if (!required.includes(check)) errors.push(`${check}: context is not a required status check`);
  }
  return errors;
}

export function loadPublicCiLocalRepro({ manifestPath = DEFAULT_REPRO_PATH, signalPolicyPath = DEFAULT_SIGNAL_POLICY_PATH } = {}) {
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const signalPolicy = JSON.parse(fs.readFileSync(signalPolicyPath, 'utf8'));
  const errors = validatePublicCiLocalRepro({ manifest, signalPolicy });
  if (errors.length > 0) throw new Error(errors.join('; '));
  return manifest;
}

export function localReproCommandMap(manifest) {
  return new Map(manifest.contexts.map((entry) => [entry.check, entry.mode === 'local-subset' ? entry.commands : []]));
}
