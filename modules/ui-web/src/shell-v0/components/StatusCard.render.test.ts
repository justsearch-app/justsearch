// @vitest-environment happy-dom

/**
 * Render tests for the StatusCard generic renderer. Covers:
 *  - Severity attribute reflection (drives CSS via :host([severity=...]) ).
 *  - Subject + reason + details rendering.
 *  - Default empty-state behavior.
 */

import { describe, expect, it } from 'vitest';
import './StatusCard.js';
import type { StatusCard, StatusCardSeverity } from './StatusCard.js';

async function mountCard(
  props: Partial<StatusCard>,
): Promise<StatusCard> {
  const el = document.createElement('jf-status-card') as StatusCard;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('StatusCard render', () => {
  it('renders the severity badge with the severity value', async () => {
    const el = await mountCard({
      severity: 'WARNING',
      subject: 'WorkerHandshake',
    });
    const badge = el.shadowRoot?.querySelector('.severity-badge');
    expect(badge?.textContent?.trim()).toBe('WARNING');
    el.remove();
  });

  it('renders subject + reason + details', async () => {
    const el = await mountCard({
      severity: 'ERROR',
      subject: 'WorkerHandshake',
      reason: 'WorkerOffline',
      details: 'index component did not respond within 5s.',
    });
    const root = el.shadowRoot;
    expect(root?.textContent).toContain('WorkerHandshake');
    expect(root?.textContent).toContain('WorkerOffline');
    expect(root?.textContent).toContain('did not respond');
    el.remove();
  });

  it('omits reason and details elements when empty', async () => {
    const el = await mountCard({ severity: 'INFO', subject: 'Idle' });
    expect(el.shadowRoot?.querySelector('.reason')).toBeNull();
    expect(el.shadowRoot?.querySelector('.details')).toBeNull();
    el.remove();
  });

  it('reflects severity to the host attribute (drives :host([severity=...]) CSS)', async () => {
    const severities: StatusCardSeverity[] = [
      'INFO',
      'WARNING',
      'ERROR',
      'UNKNOWN',
    ];
    for (const sev of severities) {
      const el = await mountCard({ severity: sev });
      expect(el.getAttribute('severity')).toBe(sev);
      el.remove();
    }
  });
});
