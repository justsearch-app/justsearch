// SPDX-License-Identifier: Apache-2.0
/**
 * adaptationProfile — the ONE adaptation/accessibility authority (569 §19 Seam 4).
 *
 * Generalises `themeState.applyAppearance`'s "write global DOM state once, let the cascade re-project
 * every surface" pattern to the user-selectable adaptation axes: density and motion. Because the
 * safety facets are co-projected, an adaptation axis costs O(1) at the projection layer with
 * structural totality — one switch re-projects every present AND future surface, and the conformance
 * gate refuses any surface that escapes it. This replaces the "add another global toggle" trajectory.
 *
 * Storage: the axes persist per-profile on `userConfig` (already persisted + signal-projected) — density
 * in `userConfig.density` (which threads to the renderers via the DensityController), motion in
 * `userConfig.accessibilityProfile`. This module is the single writer + projector.
 *
 * Tempdoc 855 §15.4/§17 R1 — CONTRAST IS NO LONGER AN AXIS HERE. It used to be, and that made two
 * independent authorities write the ONE `high-contrast` root class (this module from FE-local
 * `userConfig.accessibilityProfile.contrast`, `themeState.applyAppearance` from backend-persisted
 * `UISettings.highContrast`), with the boot call order deciding the winner. The canonical store is the
 * backend field (the only durable one), so the axis is RETIRED here rather than kept as a derived
 * read: `applyAppearance` is the single writer of the class, and the sole remaining reference to the
 * legacy `accessibilityProfile.contrast` value is {@link migrateLegacyContrastPreference}, the
 * one-time boot reconciliation that hands an existing FE-local preference to the canonical field and
 * then clears it. A derived getter was considered and rejected: every reader (SettingsSurface's
 * Accessibility switch) now reads the canonical `UISettings.highContrast` directly, so a derived
 * `contrast` field would be dead code.
 *
 * NOTE (cognitive-simplification): deferred — it needs render-level node omission (rung-2), coupled to
 * the dropped Seam 3; not a CSS-cascade axis. Only density/motion are projected here.
 */
import { getDocument, mutateDocument } from './UserStateDocument.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { applyAppearance } from './themeState.js';
import type { DensityVariant } from '../renderers/userConfig.js';
import {
  createSettingsAttempt,
  executeSettingsAttempt,
  readSettingsObservation,
} from '../../api/settingsAttempt.js';

export interface AdaptationProfile {
  readonly density?: DensityVariant;
  readonly motion?: 'full' | 'reduced';
}

/** The current adaptation profile, read from the persisted `userConfig`. */
export function getAdaptationProfile(): AdaptationProfile {
  const cfg = getDocument().userConfig;
  return {
    ...(cfg.density !== undefined ? { density: cfg.density } : {}),
    ...(cfg.accessibilityProfile?.motion !== undefined
      ? { motion: cfg.accessibilityProfile.motion }
      : {}),
  };
}

/**
 * Project the profile to global DOM state — the cascade re-projects every surface. Each axis is only
 * touched when explicitly set. Density is read by the renderers via `userConfig` (the
 * DensityController thread), not a global class.
 */
function projectToDom(p: AdaptationProfile): void {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  if (p.motion !== undefined) root.classList.toggle('motion-reduced', p.motion === 'reduced');
}

/**
 * THE single writer: merge `partial` into the persisted profile (per-profile `userConfig`), then project
 * the merged result to global DOM state. Omitted axes are untouched (like `applyAppearance`).
 */
export function applyAdaptationProfile(partial: AdaptationProfile): void {
  mutateDocument((doc) => {
    const cfg = doc.userConfig;
    const nextAccessibility = {
      ...cfg.accessibilityProfile,
      ...(partial.motion !== undefined ? { motion: partial.motion } : {}),
    };
    return {
      ...doc,
      userConfig: {
        ...cfg,
        ...(partial.density !== undefined ? { density: partial.density } : {}),
        accessibilityProfile: nextAccessibility,
      },
    };
  });
  projectToDom(getAdaptationProfile());
}

/**
 * Boot restore: project the persisted profile to global DOM. The persisted density already threads via
 * `userConfig`; this re-asserts the motion class.
 */
export function restoreAdaptationProfileOnBoot(): void {
  projectToDom(getAdaptationProfile());
}

/** Drop the retired legacy axis from the persisted profile — the "migrated" marker (see below). */
function clearLegacyContrast(profileId: string, expected: 'normal' | 'high'): void {
  mutateDocument((doc) => {
    if (doc.activeProfileId !== profileId) return doc;
    const cfg = doc.userConfig;
    const profile = cfg.accessibilityProfile;
    if (profile?.contrast !== expected) return doc;
    const { contrast: _retired, ...rest } = profile;
    return { ...doc, userConfig: { ...cfg, accessibilityProfile: rest } };
  });
}

/**
 * Tempdoc 855 §17 R1 — the ONE-TIME reconciliation that retires `accessibilityProfile.contrast`.
 *
 * Before this round both stores toggled the `high-contrast` class and the FE-local profile axis won by
 * boot call order (it projected last). Collapsing to the canonical backend field must not silently drop
 * that de-facto preference, so the migration makes the old winner win once, deliberately: an explicitly
 * set legacy value is written through to `UISettings.highContrast` via the same narrow
 * `{ui:{highContrast}}` POST the settings statechart uses, re-projected through the one appearance
 * writer, and only then cleared.
 *
 * Idempotency + no-silent-loss are both carried by "clear last": the cleared axis is the migrated
 * marker (no new flag field), and every failure path (settings unreadable, POST rejected) returns
 * WITHOUT clearing, so the preference survives to be retried on the next boot. The steady state — no
 * legacy value — returns before any network call, so this costs nothing after the first boot.
 *
 * Call AFTER `restoreAppearanceOnBoot` (which applies the canonical value): this may overturn it.
 *
 * PER-PROFILE ASYMMETRY (855 §17 unknown #2, answered statically): the legacy store is per-profile
 * (`UserStateDocument.profiles[id].userConfig`, which `getDocument().userConfig` flattens for the
 * ACTIVE profile) while the canonical backend field is singular — so high contrast is one global
 * preference from here on. That collapse is inherent to the choice of canonical store, not a slip
 * here, and the drain is per-profile rather than lossy: this reads only the active profile's value,
 * so each profile hands over ITS stored preference the first time it is the active profile at boot,
 * and clears itself. That converges (every profile migrates exactly once) and is the same
 * "last boot wins" the pre-855 code already had across a profile switch.
 */
export async function migrateLegacyContrastPreference(
  fetchImpl: typeof fetch = authorizedFetch,
): Promise<void> {
  const captured = getDocument();
  const profileId = captured.activeProfileId;
  const legacy = captured.userConfig.accessibilityProfile?.contrast;
  if (legacy === undefined) return;
  const desired = legacy === 'high';

  const isCurrent = (): boolean => {
    const current = getDocument();
    return current.activeProfileId === profileId
      && current.userConfig.accessibilityProfile?.contrast === legacy;
  };

  try {
    const data = await readSettingsObservation((path, init) => fetchImpl(path, init));
    const witness = data.witness;
    const canonical = data.ui?.highContrast === true;
    if (!isCurrent()) return;
    if (canonical === desired) {
      clearLegacyContrast(profileId, legacy);
      return;
    }

    const attempt = createSettingsAttempt({ ui: { highContrast: desired } }, witness);
    // The captured profile/value guard admits this migration intent. The attempt retains the
    // witness from the GET above; a second observation could silently replace its base.
    if (!isCurrent()) return;
    await executeSettingsAttempt((path, init) => fetchImpl(path, init), attempt);
    // COMPLETE proves this exact attempt was committed. A profile switch or user edit raced the
    // request, so do not project or clear the captured legacy preference in that case.
    if (!isCurrent()) return;
    await applyAppearance({ highContrast: desired }, fetchImpl);
    if (isCurrent()) clearLegacyContrast(profileId, legacy);
  } catch {
    // Settings endpoint unreachable or rejected — leave the legacy value in place and retry next
    // boot rather than guessing the canonical value.
    return;
  }
}
