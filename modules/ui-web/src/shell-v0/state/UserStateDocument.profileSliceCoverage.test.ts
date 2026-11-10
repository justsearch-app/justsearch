// @vitest-environment happy-dom
//
// Tempdoc 548 R1c — F11-recurrence STRUCTURAL GUARD.
//
// F11 (547) was profile-switch leaking slices: `setActiveProfileId` enumerated
// SOME per-profile slices to rebind and omitted others (savedViews,
// viewerAudience), so they carried over from the previous profile and got
// persisted onto the target. The F12 fix unified both rebind paths
// (viewFromStorage + setActiveProfileId) onto the SINGLE shared projector
// `flatSlicesFromProfile`, so they can't drift from each other.
//
// The one residual recurrence risk: `flatSlicesFromProfile` is itself an
// enumeration, so adding a new per-profile slice to `Profile` and forgetting
// to emit it here would re-introduce the leak. This guard closes that risk
// structurally — far cheaper + safer than re-architecting the central
// persisted document into a signal graph (R1c-full), whose headline benefit
// (F11 dissolution) the shared projector already delivers.
//
// Mechanism: a `Required<Profile>` fixture forces a COMPILE error if a slice
// is added to Profile without updating the fixture; the runtime loop then
// asserts the shared projector emits every fixture slice with its value.

import { describe, it, expect } from 'vitest';
import { flatSlicesFromProfile, type Profile } from './UserStateDocument.js';

describe('UserStateDocument — flatSlicesFromProfile per-slice coverage (F11 guard)', () => {
  it('emits every per-profile slice (adding a Profile slice forces this to update)', () => {
    // Required<Profile>: every slice must be present, so a newly-added
    // per-profile field makes this fixture a compile error until included.
    const full: Required<Profile> = {
      id: 'p',
      label: 'P',
      userConfig: { version: 1 },
      activeThemeId: 'theme-x',
      pinnedSearches: [{ id: 'pin', query: 'q', pinnedAt: 1, runs: [] }],
      keybindingOverrides: [{ key: 'ctrl+x', commandId: 'cmd.x', source: 'user' }],
      savedViews: [
        {
          id: 'sv',
          label: 'V',
          url: 'justsearch://search?q=x',
          surfaceId: 'core.search',
          savedAt: 1,
        },
      ],
      viewerAudience: 'OPERATOR',
      showDeveloperTools: true,
      activePresentationId: 'user.my-skin',
    };

    const flat = flatSlicesFromProfile(full) as Record<string, unknown>;
    const flatKeys = Object.keys(flat);

    // `id` / `label` are profile metadata, not flat slices — every OTHER
    // field is a per-profile slice the projector must rebind on switch.
    const sliceKeys = Object.keys(full).filter((k) => k !== 'id' && k !== 'label');

    for (const key of sliceKeys) {
      // If this fails, profile-switch would leak `key` from the prior profile
      // onto the target — the F11 class. Emit it in flatSlicesFromProfile.
      expect(flatKeys).toContain(key);
      expect(flat[key]).toEqual((full as Record<string, unknown>)[key]);
    }

    // Conversely, the projector must NOT promote profile metadata to flat slices.
    expect(flatKeys).not.toContain('id');
    expect(flatKeys).not.toContain('label');
  });
});
