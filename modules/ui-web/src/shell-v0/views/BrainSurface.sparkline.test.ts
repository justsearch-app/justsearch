// @vitest-environment happy-dom
/**
 * Tempdoc 518 Wave A-E defect Fix-10 — pin the generation sparkline rendering contract.
 *
 * Exercises `renderGenerationSparkline` via the full Lit lifecycle (mount element, set
 * state, await updateComplete, inspect shadowRoot.innerHTML). Direct `String(TemplateResult)`
 * doesn't serialize Lit templates — must render through the element.
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
import { __resetUiModeForTest, setUiMode } from '../state/uiModeState.js';

interface SparklineHost extends HTMLElement {
  settings: { mode?: 'simple' | 'advanced' };
  inference: { generation?: number; mode?: string; activeModelId?: string | null } | null;
  transitions: Array<{
    timestampMs: number;
    fromMode: string;
    toMode: string;
    reason: string;
    success: boolean;
    durationMs: number;
    wireCode?: string;
  }>;
  updateComplete: Promise<boolean>;
}

async function mountAndRender(state: {
  generation: number;
  transitions: SparklineHost['transitions'];
}): Promise<string> {
  const el = document.createElement('jf-brain-surface') as SparklineHost;
  // The transition timeline (and its sparkline) is Detailed-mode developer telemetry — it was
  // moved out of the Simple panel, which is the first-run consumer surface.
  setUiMode('advanced');
  el.settings = { mode: 'advanced' };
  el.inference = { generation: state.generation, mode: 'offline', activeModelId: null };
  el.transitions = state.transitions;
  document.body.appendChild(el);
  await el.updateComplete;
  // Scope to the sparkline itself: the Detailed panel's own chrome contains SVG icons, so a
  // whole-shadowRoot `<circle` count would no longer measure the sparkline's dots.
  const sparkline = el.shadowRoot?.querySelector('[data-testid="brain-generation-sparkline"]');
  const html = sparkline?.outerHTML ?? '';
  document.body.removeChild(el);
  return html;
}

afterEach(() => __resetUiModeForTest());

describe('BrainSurface.renderGenerationSparkline', () => {
  it('renders no sparkline when generation is 0', async () => {
    const html = await mountAndRender({
      generation: 0,
      transitions: [
        { timestampMs: 1, fromMode: 'OFFLINE', toMode: 'OFFLINE', reason: 'X', success: false, durationMs: 1 },
      ],
    });
    expect(html).not.toContain('brain-generation-sparkline');
  });

  it('renders no sparkline when no transitions are present', async () => {
    const html = await mountAndRender({ generation: 3, transitions: [] });
    expect(html).not.toContain('brain-generation-sparkline');
  });

  it('renders polyline + per-row dots when generation > 0', async () => {
    const html = await mountAndRender({
      generation: 3,
      transitions: [
        { timestampMs: 100, fromMode: 'OFFLINE', toMode: 'ONLINE', reason: 'USER_SWITCH', success: true, durationMs: 5 },
        { timestampMs: 200, fromMode: 'ONLINE', toMode: 'INDEXING', reason: 'USER_SWITCH', success: true, durationMs: 5 },
        { timestampMs: 300, fromMode: 'INDEXING', toMode: 'ONLINE', reason: 'USER_SWITCH', success: true, durationMs: 5 },
      ],
    });
    expect(html).toContain('brain-generation-sparkline');
    expect(html).toContain('brain-generation-sparkline-line');
    expect(html).toContain('<polyline');
    const circleCount = (html.match(/<circle/g) ?? []).length;
    expect(circleCount).toBe(3);
  });

  it('failures contribute dots; failure dot uses the red fill', async () => {
    const html = await mountAndRender({
      generation: 1,
      transitions: [
        { timestampMs: 100, fromMode: 'OFFLINE', toMode: 'ONLINE', reason: 'USER_SWITCH', success: true, durationMs: 5 },
        { timestampMs: 200, fromMode: 'ONLINE', toMode: 'OFFLINE', reason: 'USER_SWITCH', success: false, durationMs: 5, wireCode: 'process_died' },
      ],
    });
    // §2.C / P1c: the failure dot uses the unified danger token, not a raw red hex.
    expect(html).toContain('var(--accent-danger)');
    expect(html).not.toContain('#f87171');
    const circleCount = (html.match(/<circle/g) ?? []).length;
    expect(circleCount).toBe(2);
  });
});
