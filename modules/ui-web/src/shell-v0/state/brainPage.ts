// SPDX-License-Identifier: Apache-2.0

/** Saved On with complete files, while the running answer model is confirmed unable to read pictures. */
export const PICTURES_NOT_AVAILABLE_WITH_MODEL = 'Not available with this answer model';

export type BrainFeatureId = 'meaning' | 'reranker' | 'chat' | 'pictures';

/**
 * One step of AI Brain's Overview. The switch shows the stored choice only (`selected`, `switchWord`);
 * the step's light (`active`) and `note` show what works now, so a choice is never drawn as activity.
 */
export interface BrainFeature {
  id: BrainFeatureId;
  label: string;
  /** Observed: works now (true), does not (false), not known (null). */
  active: boolean | null;
  availability?: 'available' | 'unavailable' | 'unknown';
  /** The stored choice: the saved runtime choice once installed, the download choice before. */
  selected: boolean;
  installed: boolean;
  /** What works now when it differs from the choice, or why the step cannot work; null when the switch says it. */
  note: string | null;
  /** The step's size, the amount alone ("1.2 GB"), or null. */
  size: string | null;
  switchable: boolean;
  /** The switch's state in words; "Not known" replaces the switch. */
  switchWord: string | null;
  blockedReason: string | null;
  /** Ordinary words for a step that cannot work on this computer; drawn as not reachable. */
  unreachable: string | null;
}
