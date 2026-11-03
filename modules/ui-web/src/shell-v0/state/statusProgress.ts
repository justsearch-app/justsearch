// SPDX-License-Identifier: Apache-2.0
// The detailed readout moved from the retired task tray into the status panel.
import type { IndexingProgress } from './indexingProgress.js';
import { formatBytes, formatCount } from '../display/format.js';
import { humanizeSeconds } from './startupEstimate.js';
import { ENRICHMENT_BODY } from './enrichmentCoverage.js';
export function indexingCountsLine(p: IndexingProgress): string {
  const files = `${formatCount(p.jobsPending)} ${p.jobsPending === 1 ? 'file' : 'files'} remaining`;
  const weighed = p.pendingBytes === null ? files : `${files} · ${formatBytes(p.pendingBytes)}`;
  return p.etaSeconds === null ? weighed : `${weighed} · ~${humanizeSeconds(p.etaSeconds)} left`;
}

/**
 * The accessible/hover form of {@link indexingCountsLine} — the same facts plus the estimate's
 * INDICATIVE qualifier, which 813 §19 (W4) moved off the visible line to keep the row to one line
 * without dropping the honesty it carries. `null` when there is no estimate, so the surface attaches
 * no label at all rather than one that repeats the visible text.
 */
export function indexingCountsLabel(p: IndexingProgress): string | null {
  return p.etaSeconds === null ? null : `${indexingCountsLine(p)} at the current rate`;
}

/**
 * The Enriching phase's fact row (813 §20): the coverage percent when the projection has a faithful
 * one, the shared caveat, and the enrichment estimate when the projection has an honest basis for
 * one — built with the same segment discipline as {@link indexingCountsLine}, including the same
 * humanizer, so the two phases speak one dialect.
 *
 * The percent here is the CAPABILITY-tier number (how far the whole blend has come). The per-stage
 * breakdown that adds up to it lives in the disclosure — §20's two layers.
 */
export function enrichingCountsLine(p: IndexingProgress): string {
  const base =
    p.enrichingPercent === null ? ENRICHMENT_BODY : `${p.enrichingPercent}% · ${ENRICHMENT_BODY}`;
  return p.enrichingEtaSeconds === null
    ? base
    : `${base} · ~${humanizeSeconds(p.enrichingEtaSeconds)} left`;
}

/** {@link enrichingCountsLine} plus the INDICATIVE qualifier (§19 W4's accessible-label form). */
export function enrichingCountsLabel(p: IndexingProgress): string | null {
  return p.enrichingEtaSeconds === null ? null : `${enrichingCountsLine(p)} at the current rate`;
}

