// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 914 D3 (review S2-2 + delta nit) — the failed chip's LOOK, authored once.
 *
 * The chip has two render sites (LibrarySurface's hand-authored card and the declared
 * FolderCardRenderer) in two shadow roots, so a shared stylesheet is the only way they cannot drift.
 * `failedChipCopy` in `state/folderStatus.ts` already does this for the chip's WORDS; this is the
 * same single-authority move for the treatment that carries the same meaning, and the same reason to
 * change — "how a last-known count looks" is one design decision, not two.
 *
 * The muted+italic pair is not invented here: it is the treatment `StatusDeck`'s `.val.stale` rule
 * already gives a last-known value, so the shell says "this number is carried" one way everywhere.
 *
 * Mirrors the house pattern for a shared css fragment (`resultRowPresentation.ts`'s
 * `highlightStyles`, `atRestCard.ts`'s `atRestCardStyles`): a small presentation module the
 * consuming components fold into their own `static styles`.
 */
import { css } from 'lit';

export const failedChipStyles = css`
  /* Tempdoc 599 §16/B1 — the clickable "N failed" chip (danger tone) goes to the folder's failed files. */
  .failed-chip {
    margin-left: 0.4rem;
    --jf-button-color: var(--text-danger);
    color: var(--text-danger);
  }
  /* Tempdoc 914 D3 / review S2-2 — a LAST-KNOWN count is muted + italic, so the qualifier the
     accessible name and the hover title carry in words is visible to sighted users too. */
  .failed-chip[data-last-known='true'] {
    --jf-button-color: var(--text-muted);
    color: var(--text-muted);
    font-style: italic;
  }
`;
