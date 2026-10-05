/**
 * Source-of-intent checks (tempdoc 966 D3, D1 "Coverage").
 *
 * What the gate can check mechanically, and checks:
 *   - the source kind is one D3 names (task scope, a heading or a brief is never a citation);
 *   - every field the kind needs is present (quote, location, scope, label, reproduction, ...);
 *   - a cited repository document RESOLVES at the base, the quote appears in it verbatim
 *     (whitespace-insensitive), and the document was not added or modified in this PR — a source
 *     written in the PR is circular;
 *   - an owner-adopted scenario is cited by task and scenario id, quoted, and labelled as such
 *     (the gate cannot see task records, so it checks the form and the quote's presence);
 *   - a delegated choice quotes the delegation, states its scope, and labels the choice an agent
 *     choice;
 *   - a defect source names a safeguard or a stronger source, plus a reproduction;
 *   - a removal source (P6) names production files this PR deletes or modifies.
 *
 * What it cannot check, and leaves to the acceptor (D2): that the quote supports the expectation,
 * that a decision record records the OWNER's decision, that a delegated choice lies within the
 * delegation's scope, that an "owner decision" label in an agent document has owner words behind it.
 */

import {
  EXPECTATION_SOURCE_KINDS,
  NOT_A_CITATION_KINDS,
  SAFEGUARDS,
  SOURCE_KINDS,
} from './classifications.mjs';
import { classifyPath } from './scope.mjs';

const nonEmpty = (v) => typeof v === 'string' && v.trim().length > 0;
const squash = (s) => s.replace(/\s+/g, ' ').trim();

const TASK_LOCATION = /^task\s+(\S+)\s+(request|user turn(?:\s+\S+)?|agreement(?:\s+step)?|turn\s+\S+)\b/i;
const REPO_LOCATION = /^([A-Za-z0-9_.][^:\s]*\.[A-Za-z0-9]+)(?::(\d+)(?:-(\d+))?)?$/;
const TASK_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{2,}$/;
const SCENARIO_ID = /^[A-Z]{1,3}\d+[a-z]?$/;

/**
 * @typedef {{
 *   readBase: (rel: string) => string|null,
 *   changedPaths: Set<string>,
 *   changeStatus: Map<string, string>,
 * }} SourceContext
 */

/**
 * Validate a location + quote pair.
 * @returns {Array<{rule: string, message: string}>}
 */
export function checkLocation(location, quote, ctx, { mustBeUnder = null, label = 'location' } = {}) {
  const errors = [];
  if (!nonEmpty(location)) return [{ rule: 'entry-invalid', message: `${label} is missing` }];
  const loc = location.trim();
  if (TASK_LOCATION.test(loc)) {
    if (mustBeUnder) {
      errors.push({ rule: 'source-unresolved', message: `${label} '${loc}' must be a file under ${mustBeUnder}` });
    }
    return errors;
  }
  const m = REPO_LOCATION.exec(loc);
  if (!m) {
    return [{
      rule: 'source-unresolved',
      message: `${label} '${loc}' is neither a repository file (path[:line]) nor a task record ` +
        "('task <id> request', 'task <id> user turn <n>', 'task <id> agreement')",
    }];
  }
  const rel = m[1];
  if (mustBeUnder && !rel.startsWith(mustBeUnder)) {
    errors.push({ rule: 'source-unresolved', message: `${label} '${rel}' must be under ${mustBeUnder}` });
  }
  if (ctx.changedPaths.has(rel)) {
    const word = ctx.changeStatus.get(rel) === 'A' ? 'added' : 'modified';
    errors.push({
      rule: 'source-written-in-pr',
      message: `${label} '${rel}' was ${word} in this PR; a source written in the PR is circular (D1). ` +
        'Cite a source that predates the PR, or the task\'s agreed scenarios by task and scenario id.',
    });
    return errors;
  }
  const text = ctx.readBase(rel);
  if (text === null) {
    errors.push({ rule: 'source-unresolved', message: `${label} '${rel}' does not exist at the base` });
    return errors;
  }
  if (m[2]) {
    const lines = text.split(/\r?\n/).length;
    if (Number(m[2]) > lines || (m[3] && Number(m[3]) > lines)) {
      errors.push({ rule: 'source-unresolved', message: `${label} '${loc}' points past the end of the file (${lines} lines)` });
    }
  }
  if (nonEmpty(quote) && !squash(text).includes(squash(quote))) {
    errors.push({ rule: 'source-unresolved', message: `the quote does not appear verbatim in '${rel}'` });
  }
  return errors;
}

/**
 * @param {any} src one source object
 * @param {SourceContext} ctx
 * @param {{allowRemoval?: boolean}} [opts]
 * @returns {Array<{rule: string, message: string}>}
 */
export function validateSource(src, ctx, opts = {}) {
  if (!src || typeof src !== 'object') return [{ rule: 'entry-invalid', message: 'a source must be an object with a "kind"' }];
  const kind = src.kind;
  if (NOT_A_CITATION_KINDS.includes(kind) || (!SOURCE_KINDS.includes(kind) && /task|scope|heading/i.test(String(kind)))) {
    return [{
      rule: 'source-not-a-citation',
      message: `source kind '${kind}' is not a citation: task scope or a heading never licenses an expectation ` +
        '(D2 Obsolete). Cite owner words, an owner-adopted scenario, a delegated choice, a defect source or a decision record.',
    }];
  }
  if (!SOURCE_KINDS.includes(kind)) {
    return [{ rule: 'entry-invalid', message: `unknown source kind '${kind}' (allowed: ${SOURCE_KINDS.join(', ')})` }];
  }
  const errors = [];
  const need = (field) => {
    if (!nonEmpty(src[field])) errors.push({ rule: 'entry-invalid', message: `${kind} source needs '${field}'` });
  };
  switch (kind) {
    case 'owner-words':
      need('quote');
      errors.push(...checkLocation(src.location, src.quote, ctx));
      break;
    case 'owner-adopted-scenario':
      need('quote');
      if (!nonEmpty(src.task) || !TASK_ID.test(src.task.trim())) {
        errors.push({ rule: 'entry-invalid', message: "owner-adopted-scenario needs 'task' (the task id the scenarios were agreed in)" });
      }
      if (!nonEmpty(src.scenario) || !SCENARIO_ID.test(src.scenario.trim())) {
        errors.push({ rule: 'entry-invalid', message: "owner-adopted-scenario needs 'scenario' (its id, e.g. S4)" });
      }
      if (!nonEmpty(src.label) || !/owner-adopted/i.test(src.label) || !/agent-drafted/i.test(src.label)) {
        errors.push({ rule: 'entry-invalid', message: "owner-adopted-scenario needs 'label': \"agent-drafted, owner-adopted\" (D3)" });
      }
      break;
    case 'delegated-choice':
      need('delegationQuote');
      need('delegationScope');
      need('choice');
      errors.push(...checkLocation(src.delegationLocation, src.delegationQuote, ctx, { label: 'delegationLocation' }));
      if (!nonEmpty(src.label) || !/agent choice/i.test(src.label)) {
        errors.push({ rule: 'entry-invalid', message: "delegated-choice needs 'label': \"agent choice\" (D3)" });
      }
      break;
    case 'defect-source': {
      need('reproduction');
      const hasSafeguard = nonEmpty(src.safeguard) && SAFEGUARDS.includes(src.safeguard.trim().toLowerCase());
      if (nonEmpty(src.safeguard) && !hasSafeguard) {
        errors.push({ rule: 'entry-invalid', message: `safeguard '${src.safeguard}' is not one of: ${SAFEGUARDS.join(', ')}` });
      }
      if (src.stronger !== undefined) {
        if (!EXPECTATION_SOURCE_KINDS.includes(src.stronger?.kind)) {
          errors.push({ rule: 'entry-invalid', message: `defect-source 'stronger' must be one of: ${EXPECTATION_SOURCE_KINDS.join(', ')}` });
        } else {
          errors.push(...validateSource(src.stronger, ctx));
        }
      }
      if (!hasSafeguard && src.stronger === undefined && !nonEmpty(src.safeguard)) {
        errors.push({ rule: 'entry-invalid', message: "defect-source needs 'safeguard' (no hang | no data loss | no crash | no leak across a requested scope) or 'stronger' (a stronger D3 source)" });
      }
      break;
    }
    case 'decision-record':
      need('quote');
      errors.push(...checkLocation(src.location, src.quote, ctx, { mustBeUnder: 'docs/decisions/' }));
      break;
    case 'removal': {
      if (!opts.allowRemoval) {
        errors.push({ rule: 'entry-invalid', message: "a 'removal' source only supports an Obsolete entry (P6)" });
        break;
      }
      const removed = Array.isArray(src.removed) ? src.removed : [];
      if (removed.length === 0) errors.push({ rule: 'entry-invalid', message: "removal source needs 'removed': [production paths]" });
      for (const rel of removed) {
        if (classifyPath(rel)) {
          errors.push({ rule: 'entry-invalid', message: `removal source '${rel}' is test code; name the production code removed` });
        } else if (!ctx.changedPaths.has(rel) || ctx.changeStatus.get(rel) === 'A') {
          errors.push({ rule: 'source-unresolved', message: `removal source '${rel}' is not deleted or modified in this PR` });
        }
      }
      break;
    }
    default:
      break;
  }
  return errors;
}
