// @vitest-environment happy-dom

/**
 * Tempdoc 806 B.2 (round-12): ONE condition, ONE named remedy.
 *
 * The Brain SIMPLE panel tells the user "A required component is missing — use Repair in Detailed"
 * for `installStatus.repairNeeded`. The Detailed panel it sends them to presented **Install** as the
 * primary CTA for that same condition, with Repair as an unemphasised sibling — the user follows a
 * named instruction and the destination names a different action.
 *
 * These assertions read the rendered buttons, not a helper, because the defect was which button
 * looked primary on the screen the user was sent to.
 */

import { afterEach, describe, expect, it, beforeEach, vi } from 'vitest';

// Incidental requests are outside these render/event scenarios. Keep their
// unavailable-backend behavior explicit, without contacting a real server.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });
import './BrainSurface';
import { deriveRepairRemedy, repairRemedySub } from './BrainSurface.js';
import type { InstallStatus } from '../state/aiStateStore.js';
import { __resetUiModeForTest, setUiMode } from '../state/uiModeState.js';

interface BrainHost extends HTMLElement {
  apiBase: string;
  settings: { mode?: 'simple' | 'advanced' };
  installStatus: InstallStatus | null;
  expanded: Record<string, boolean>;
  requestUpdate(): void;
  updateComplete: Promise<boolean>;
}

/** Mounts Detailed with the AI-install accordion open and returns the install-section buttons. */
async function installButtons(
  installStatus: InstallStatus,
): Promise<{ variants: Record<string, string | null>; text: string }> {
  const el = document.createElement('jf-brain-surface') as BrainHost;
  el.apiBase = '';
  setUiMode('advanced');
  el.settings = { mode: 'advanced' };
  document.body.appendChild(el);
  await el.updateComplete;
  el.installStatus = installStatus;
  el.expanded = { install: true };
  el.requestUpdate();
  await el.updateComplete;
  const variants: Record<string, string | null> = {};
  for (const b of el.shadowRoot?.querySelectorAll('jf-button') ?? []) {
    const label = b.getAttribute('label');
    if (label === 'Install' || label === 'Repair' || label === 'Cancel') {
      variants[label] = b.getAttribute('variant');
    }
  }
  const text = el.shadowRoot?.textContent ?? '';
  document.body.removeChild(el);
  return { variants, text };
}

const REPAIR_NEEDED: InstallStatus = {
  state: 'idle',
  phase: 'idle',
  installedFully: true,
  repairNeeded: true,
} as InstallStatus;

const HEALTHY: InstallStatus = {
  state: 'idle',
  phase: 'idle',
  installedFully: true,
} as InstallStatus;

afterEach(() => __resetUiModeForTest());

describe('BrainSurface Detailed — the install panel names the same remedy Simple points at', () => {
  it('repairNeeded ⇒ Repair is the primary affordance, Install is not', async () => {
    const { variants, text } = await installButtons(REPAIR_NEEDED);
    expect(variants.Repair).toBe('primary');
    expect(variants.Install).not.toBe('primary');
    // The panel states the condition in the same words Simple used, so the two read as one story.
    expect(text).toContain('A required component is missing');
    expect(text).toContain('use Repair');
  });

  it('no repair needed ⇒ Install stays primary (the pre-existing default is untouched)', async () => {
    const { variants, text } = await installButtons(HEALTHY);
    expect(variants.Install).toBe('primary');
    expect(variants.Repair).not.toBe('primary');
    expect(text).not.toContain('A required component is missing');
  });
});

/**
 * Tempdoc 824 §3.3c/§3.4 — the sentence has to be true, not just actionable.
 *
 * Round 16 rendered "A required component is missing" over a machine whose only missing file was an
 * 872-byte optional metadata sidecar, while SPLADE was serving 1 660 inferences on CUDA. Three
 * separate claims were being collapsed into one: is a file missing, does any consumer need it, and
 * is the capability actually down.
 */
const OPTIONAL_GAP_ONLY: InstallStatus = {
  state: 'completed',
  phase: 'done',
  installedFully: true,
  repairNeeded: false,
  optionalGaps: [{ packageId: 'splade', fileName: 'config.json' }],
  packages: [{ packageId: 'splade', label: 'Sparse retrieval (SPLADE)', state: 'failed' }],
} as InstallStatus;

const REQUIRED_MISSING_BUT_ACTIVE: InstallStatus = {
  state: 'completed',
  phase: 'done',
  installedFully: false,
  repairNeeded: true,
  packages: [
    {
      packageId: 'splade',
      label: 'Sparse retrieval (SPLADE)',
      state: 'failed',
      functionalStatus: 'active',
    },
  ],
} as InstallStatus;

const REQUIRED_MISSING_AND_DOWN: InstallStatus = {
  state: 'completed',
  phase: 'done',
  installedFully: false,
  repairNeeded: true,
  packages: [
    {
      packageId: 'splade',
      label: 'Sparse retrieval (SPLADE)',
      state: 'failed',
      functionalStatus: 'inactive',
    },
  ],
} as InstallStatus;

const NON_CONVERGENT: InstallStatus = {
  state: 'completed',
  phase: 'done',
  installedFully: false,
  repairNeeded: true,
  packages: [
    {
      packageId: 'splade',
      label: 'Sparse retrieval (SPLADE)',
      state: 'failed',
      functionalStatus: 'inactive',
      terminalReason: 'TRANSPORT_UNAVAILABLE',
      attempts: 12,
      error: 'Download failed for splade/naver-splade-v3/config.json',
      url: 'https://example/splade-config.json',
      targetPath: 'C:\\models\\splade\\naver-splade-v3\\config.json',
    },
  ],
} as InstallStatus;

describe('BrainSurface — the repair claim names its authority', () => {
  it('optional-only gap ⇒ no repair prompt at all, and the gap is still named', async () => {
    expect(deriveRepairRemedy(OPTIONAL_GAP_ONLY)).toEqual({ kind: 'none' });
    expect(repairRemedySub({ kind: 'none' })).toBeNull();

    const { variants, text } = await installButtons(OPTIONAL_GAP_ONLY);
    expect(text).not.toContain('A required component is missing');
    expect(variants.Install).toBe('primary');
    expect(text).toContain('Optional files not installed');
    expect(text).toContain('splade/config.json');
  });

  it('required file missing but the capability is observably running ⇒ the "working, but" copy', async () => {
    expect(deriveRepairRemedy(REQUIRED_MISSING_BUT_ACTIVE)).toEqual({ kind: 'repair-soft' });
    expect(repairRemedySub({ kind: 'repair-soft' })).toBe(
      'Working, but an expected file is missing — Repair will restore it.',
    );

    const { variants, text } = await installButtons(REQUIRED_MISSING_BUT_ACTIVE);
    expect(text).toContain('Working, but an expected file is missing');
    expect(text).not.toContain('A required component is missing');
    // It is still a gap, so Repair is still the action — only the alarm is dropped.
    expect(variants.Repair).toBe('primary');
  });

  it('required file missing and the capability is down ⇒ the full-strength copy is unchanged', async () => {
    expect(deriveRepairRemedy(REQUIRED_MISSING_AND_DOWN)).toEqual({ kind: 'repair' });

    const { variants, text } = await installButtons(REQUIRED_MISSING_AND_DOWN);
    expect(text).toContain('A required component is missing');
    expect(variants.Repair).toBe('primary');
  });

  it('no observation at all ⇒ fails closed to the full-strength copy', () => {
    // The disk recompute after a restart cannot attribute the gap to a capability; an unobserved
    // capability must never soften the claim.
    expect(deriveRepairRemedy({ state: 'idle', phase: 'idle', repairNeeded: true } as InstallStatus)).toEqual({
      kind: 'repair',
    });
    // A failed package whose capability was never observed is 'unknown', not 'active'.
    expect(
      deriveRepairRemedy({
        state: 'completed',
        phase: 'done',
        repairNeeded: true,
        packages: [{ packageId: 'splade', state: 'failed', functionalStatus: 'unknown' }],
      } as InstallStatus),
    ).toEqual({ kind: 'repair' });
  });

  it('three failed repair passes ⇒ the manual fallback, not an unqualified Repair button', async () => {
    const remedy = deriveRepairRemedy(NON_CONVERGENT);
    expect(remedy.kind).toBe('manual');

    const { variants, text } = await installButtons(NON_CONVERGENT);
    // The defect this pins: an affordance that provably cannot succeed presented as THE remedy.
    expect(variants.Repair).not.toBe('primary');
    expect(variants.Install).not.toBe('primary');
    expect(text).toContain('Automatic repair could not download');
    expect(text).toContain('https://example/splade-config.json');
    expect(text).toContain('config.json');
    expect(text).toContain('12 attempts');
    expect(text).not.toContain('A required component is missing — use Repair.');
  });
});
