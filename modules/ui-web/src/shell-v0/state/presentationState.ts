// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 1 / Fix D — THE single apply writer + THE one catalog for the whole Presentation
 * Declaration. Collapses the previously-split apply paths: the THEME tier rides the one appearance
 * writer (`themeState.applyTheme`, which injects the scoped `@layer user-theme` style + persists the
 * active id), and the BODY/LAYOUT/INTERACTION tiers ride the runtime store (`applyPresentationBodies`,
 * which certifies + quarantines). One declaration → one writer → one catalog (built-in ∪ user).
 *
 * "Apply" is this writer un-persisted-to-catalog (it is live but not saved as a custom skin); "save"
 * is `saveCustomPresentation`; "preview" is `previewPresentation` (certify without applying). A team
 * default, a user skin, and an LLM skin are the same artifact with a different origin (Move 7).
 */
import {
  certifyPresentation,
  type CertifyResult,
} from '../themes/conformanceGate.js';
import { applyPresentationBodies, type ApplyResult } from './presentationRuntime.js';
import { applyTheme } from './themeState.js';
import { deriveRoleForegrounds } from '../themes/roleForegrounds.js';
import { isSafeTokenValue } from '../themes/designTokenTree.js';
import { listCustomPresentations } from '../themes/presentationCatalog.js';
import { BUILTIN_PRESENTATIONS, CORE_DECLARED } from '../themes/builtinPresentations.js';
import type { PresentationDeclaration } from '../themes/presentationDeclaration.js';
import { getDocument, hasNewerStoredUserState, mutateDocument } from './UserStateDocument.js';

/** 569 §19 Seam 6 — cap the apply history so it cannot grow unbounded. */
const PRESENTATION_HISTORY_CAP = 50;

/** Compile a token map to a scoped `:root` rule. Values are {@link isSafeTokenValue}-checked — no
 * brace/angle breakout — so the host emits the CSS, the author never supplies a rule. */
function themeTierToCss(tokens: Readonly<Record<string, string>>): string {
  const decls = Object.entries(tokens)
    .filter(([, v]) => isSafeTokenValue(v))
    .map(([k, v]) => `  --${k}: ${v};`)
    .join('\n');
  return `:root {\n${decls}\n}`;
}

/**
 * THE single writer: certify once, apply the theme tier through the one appearance writer, and the
 * body/layout/interaction tiers through the runtime store. A hard-invalid declaration applies
 * nothing; individually-failing bodies are quarantined to the default (degrade-never-fail).
 */
export function applyPresentation(candidate: unknown): ApplyResult {
  const { declaration, verdict } = certifyPresentation(candidate);
  if (!declaration) return { ok: false, errors: verdict.errors, quarantined: [] };
  if (declaration.theme && Object.keys(declaration.theme.tokens).length > 0) {
    applyTheme(declaration.id, themeTierToCss(declaration.theme.tokens));
    deriveRoleForegrounds(); // 558 co-projection follows the new fill
  }
  // applyPresentationBodies re-certifies (cheap, idempotent) and owns body/layout quarantine.
  const result = applyPresentationBodies(declaration);
  // 569 §19 Seam 5/6 — persist the active id (so boot restores it) + append to the apply history.
  persistActivePresentation(declaration.id);
  return result;
}

/**
 * 569 §19 Seam 5/6 — record `id` as the active presentation (per-profile) and append it to the
 * cross-profile apply history (dedup-consecutive so a boot re-apply of the same id is not a new
 * entry; capped at {@link PRESENTATION_HISTORY_CAP}).
 */
function persistActivePresentation(id: string): void {
  mutateDocument((doc) => {
    const history = doc.presentationHistory ?? [];
    const lastId = history[history.length - 1]?.presentationId;
    const nextHistory =
      lastId === id
        ? history
        : [...history, { presentationId: id, appliedAt: Date.now() }].slice(
            -PRESENTATION_HISTORY_CAP,
          );
    if (doc.activePresentationId === id && nextHistory === history) return doc;
    return { ...doc, activePresentationId: id, presentationHistory: nextHistory };
  });
}

/**
 * 569 §19 Seam 6 — revert to the previously-applied declaration: pop the current history entry and
 * re-apply the one before it. Returns the apply result, or null if there is nothing to revert to or
 * the prior declaration no longer resolves in the catalog.
 */
export function revertPresentation(): ApplyResult | null {
  const history = getDocument().presentationHistory ?? [];
  if (history.length < 2) return null;
  const prev = history[history.length - 2];
  if (!prev) return null;
  const prevId = prev.presentationId;
  const decl = listPresentations().find((p) => p.id === prevId);
  if (!decl) return null;
  // Pop the current (newest) entry so the revert is a real step back, not a forward apply.
  mutateDocument((doc) => ({
    ...doc,
    presentationHistory: (doc.presentationHistory ?? []).slice(0, -1),
  }));
  // applyPresentation now sees `prevId` as the history tail → dedup skips a re-append.
  return applyPresentation(decl);
}

/**
 * 569 §19 Seam 5 — boot restore: re-apply the persisted active declaration if it still resolves,
 * else apply the built-in default. Called from `main.jsx` between `restoreAppearanceOnBoot` and
 * surface mount; goes through the same certify + degrade-never-fail path.
 */
export function restoreActivePresentationOnBoot(): ApplyResult {
  const id = getDocument().activePresentationId;
  if (id) {
    const decl = listPresentations().find((p) => p.id === id);
    if (decl) return applyPresentation(decl);
  }
  // A future document gives this build only an in-memory default view. Applying the default's
  // bodies is safe; persisting its identity/history here would destroy that document on every boot.
  if (hasNewerStoredUserState()) return applyPresentationBodies(CORE_DECLARED);
  return applyPresentation(CORE_DECLARED);
}

/** Certify a candidate WITHOUT applying — the authoring preview's validation step. */
export function previewPresentation(candidate: unknown): CertifyResult {
  return certifyPresentation(candidate);
}

/** The ONE catalog: built-in defaults ∪ user declarations (user id wins on collision). */
export function listPresentations(): readonly PresentationDeclaration[] {
  const custom = listCustomPresentations();
  const customIds = new Set(custom.map((p) => p.id));
  return [...BUILTIN_PRESENTATIONS.filter((p) => !customIds.has(p.id)), ...custom];
}

/** Look up one declaration by id across the unified catalog. */
export function getPresentation(id: string): PresentationDeclaration | undefined {
  return listPresentations().find((p) => p.id === id);
}
