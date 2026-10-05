/**
 * test-intent truth table — tempdoc 966 D1, D2, Measurement.
 *
 * Pure verdict functions in the substrate's `(input) → { ruleId, status, reason }` shape
 * (scripts/governance/lib/truth-table-runner.mjs). The enforcer routes every coverage and
 * acceptance decision through these, so the decision rules are readable in one place.
 */

/**
 * Coverage of one flagged item.
 *
 * @param {{id: string, fullEntries: number, refs: number, referable: boolean, fork?: boolean}} input
 */
export function verdictForItem({ id, fullEntries, refs, referable }) {
  if (fullEntries === 0 && refs === 0) {
    return { ruleId: 'test-intent/uncovered', status: 'fail', reason: `${id} has no test-intent entry` };
  }
  if (refs > 0 && !referable) {
    return {
      ruleId: 'test-intent/reference-not-allowed',
      status: 'fail',
      reason: `${id} cannot be covered by a test-efficacy reference; it needs a full entry (suppression-ratchet raises, ` +
        'policy entries and ArchUnit store changes carry no intent source)',
    };
  }
  if (refs > 0 && fullEntries > 0) {
    return {
      ruleId: 'test-intent/second-entry',
      status: 'fail',
      reason: `${id} is accounted for by a test-efficacy changeset; it needs the reference only, not a second entry`,
    };
  }
  return { ruleId: 'test-intent/covered', status: 'pass', reason: `${id} is covered` };
}

/**
 * Acceptance of one changeset that has entries.
 *
 * @param {{changeset: string, authorSession: string|null, digest: string,
 *          records: Array<{kind?: string, role?: string, session?: string, verdict?: string, digest?: string}>,
 *          auditSelected: boolean, prNumber: number|null}} input
 */
export function verdictForAcceptance({ changeset, authorSession, digest, records, auditSelected, prNumber }) {
  const fail = (rule, reason) => ({ ruleId: `test-intent/${rule}`, status: 'fail', reason });
  if (!authorSession) {
    return fail('acceptance-missing', `${changeset}: frontmatter 'author-session' is missing, so non-author acceptance cannot be checked`);
  }
  const current = records.filter((r) => r?.digest === digest);
  const acceptances = current.filter((r) => (r.kind ?? 'acceptance') === 'acceptance');
  const valid = acceptances.filter(
    (r) => ['verifier', 'orchestrator'].includes(r.role) && typeof r.session === 'string' && r.session.trim() && r.session !== authorSession,
  );
  if (valid.length === 0) {
    if (acceptances.some((r) => r.session === authorSession)) {
      return fail('acceptor-is-author', `${changeset}: the acceptance record was written by the author's session; the acceptor must be someone else (D2)`);
    }
    if (acceptances.length > 0) {
      return fail('acceptance-missing', `${changeset}: the acceptance record needs role 'verifier' or 'orchestrator' and a session id`);
    }
    if (records.length > 0) {
      return fail('acceptance-stale', `${changeset}: no acceptance record matches the current digest ${digest}; a flagged item, ` +
        'an entry or an evidence file changed after acceptance. The acceptor re-reviews and writes a new record.');
    }
    return fail('acceptance-missing', `${changeset}: no acceptance record (current digest ${digest})`);
  }
  // Any rejection on the current digest blocks. It is cleared only by changed content (a new
  // digest, which leaves the rejection behind as stale); an acceptance by a different reviewer on
  // the same digest is a disagreement that only a third reviewer's verdict settles.
  const participants = new Set(valid.map((r) => r.session));
  const rejects = valid.filter((r) => r.verdict !== 'accept');
  const accepts = valid.filter((r) => r.verdict === 'accept');
  let disagreement = null;
  let how;
  if (rejects.length > 0) {
    const other = accepts.find((a) => rejects.some((r) => r.session !== a.session));
    if (!other) {
      return fail('acceptance-rejected', `${changeset}: rejected by ${rejects.map((r) => r.session).join(', ')} on the current content; ` +
        'a rejection is cleared only by changed content (rework the entries or the flagged items, then get a new record)');
    }
    const rejecter = rejects.find((r) => r.session !== other.session);
    disagreement = `acceptors disagree (${rejecter.session}: reject, ${other.session}: accept)`;
  } else {
    const primary = accepts[accepts.length - 1];
    how = `accepted by ${primary.role} ${primary.session}`;
  }
  if (auditSelected) {
    const audits = current.filter((r) => r.kind === 'audit' && r.session && r.session !== authorSession && !participants.has(r.session));
    if (audits.length === 0) {
      return fail('audit-required', `${changeset}: PR #${prNumber} is audit-selected (every fifth PR by number); it needs a second ` +
        "record (kind 'audit') from a reviewer with a different session than the author and the acceptor");
    }
    const audit = audits[audits.length - 1];
    participants.add(audit.session);
    if (!disagreement && audit.verdict !== 'accept') {
      disagreement = `acceptor (accept) and auditor ${audit.session} (${audit.verdict}) disagree`;
    } else if (!disagreement) {
      how += `, audited by ${audit.session}`;
    }
  }
  let decision = 'accept';
  if (disagreement) {
    const breaks = current.filter((r) => r.kind === 'tie-break' && r.session && r.session !== authorSession && !participants.has(r.session));
    if (breaks.length === 0) {
      return fail('tie-break-required', `${changeset}: ${disagreement}; a third agent reviewer (not the author, an acceptor or ` +
        "the auditor) writes a 'tie-break' record whose verdict decides");
    }
    const tieBreak = breaks[breaks.length - 1];
    decision = tieBreak.verdict;
    how = `${disagreement}; tie-break by ${tieBreak.session}`;
  }
  if (decision !== 'accept') {
    return fail('acceptance-rejected', `${changeset}: the entries were rejected (${how}); rework them and get a new record`);
  }
  return { ruleId: 'test-intent/accepted', status: 'pass', reason: `${changeset}: ${how}` };
}
