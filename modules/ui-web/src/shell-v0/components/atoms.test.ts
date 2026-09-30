// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest';
import './StatusDot.js';
import './Button.js';
import './StatusBadge.js';
import './ErrorAlert.js';
import type { StatusDot } from './StatusDot.js';
import type { JfButton } from './Button.js';
import type { StatusBadge } from './StatusBadge.js';
import type { ErrorAlert } from './ErrorAlert.js';

async function mount<T extends HTMLElement>(tag: string, setup: (el: T) => void): Promise<T> {
  const el = document.createElement(tag) as T;
  setup(el);
  document.body.appendChild(el);
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  return el;
}

describe('jf-status-dot (574 atom)', () => {
  it('projects colour from the status tone (statusTone authority)', async () => {
    const el = await mount<StatusDot>('jf-status-dot', (c) => (c.status = 'failed'));
    const dot = el.shadowRoot!.querySelector('.dot') as HTMLElement;
    expect(dot.getAttribute('style')).toContain('--accent-danger');
  });
  it('reflects the live attribute so the pulse can key off it', async () => {
    const idle = await mount<StatusDot>('jf-status-dot', (c) => (c.status = 'ok'));
    expect(idle.hasAttribute('live')).toBe(false);
    const live = await mount<StatusDot>('jf-status-dot', (c) => {
      c.status = 'running';
      c.live = true;
    });
    expect(live.hasAttribute('live')).toBe(true);
  });
  it('is decorative by default, an image when labelled', async () => {
    const plain = await mount<StatusDot>('jf-status-dot', (c) => (c.status = 'ok'));
    expect(plain.shadowRoot!.querySelector('.dot')!.getAttribute('role')).toBe('presentation');
    const labelled = await mount<StatusDot>('jf-status-dot', (c) => {
      c.status = 'ok';
      c.label = 'Online';
    });
    const node = labelled.shadowRoot!.querySelector('.dot')!;
    expect(node.getAttribute('role')).toBe('img');
    expect(node.getAttribute('aria-label')).toBe('Online');
  });
});

describe('jf-button (574 atom)', () => {
  it('composes jf-control for operability and fires onActivate', async () => {
    const onActivate = vi.fn();
    const el = await mount<JfButton>('jf-button', (c) => {
      c.label = 'Go';
      c.onActivate = onActivate;
    });
    const control = el.shadowRoot!.querySelector('jf-control');
    expect(control).toBeTruthy();
    await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    (control!.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
    expect(onActivate).toHaveBeenCalledTimes(1);
  });
  it('reflects the variant attribute for skinning', async () => {
    const el = await mount<JfButton>('jf-button', (c) => {
      c.variant = 'primary';
      c.label = 'Save';
    });
    expect(el.getAttribute('variant')).toBe('primary');
  });
  it('projects a solid CTA fill from tone (statusTone authority, wins over variant)', async () => {
    const el = await mount<JfButton>('jf-button', (c) => {
      c.tone = 'success';
      c.label = 'Submit';
    });
    expect(el.getAttribute('tone')).toBe('success');
    // The solid tone rule is keyed off the reflected attribute; presence is the contract the
    // CSS (background: var(--accent-success); color: var(--accent-on-success)) hangs on.
    expect(el.matches('[tone="success"]')).toBe(true);
  });
  it('reflects size="icon" for the square icon-only skin (label still names it)', async () => {
    const el = await mount<JfButton>('jf-button', (c) => {
      c.size = 'icon';
      c.label = 'Close';
    });
    expect(el.getAttribute('size')).toBe('icon');
    const control = el.shadowRoot!.querySelector('jf-control');
    expect((control as unknown as { label: string }).label).toBe('Close');
  });
});

describe('jf-status-badge (574 atom)', () => {
  it('projects tinted bg + text-grade fg from the tone', async () => {
    const el = await mount<StatusBadge>('jf-status-badge', (c) => {
      c.status = 'completed';
      c.label = 'Healthy';
    });
    const badge = el.shadowRoot!.querySelector('.badge') as HTMLElement;
    const style = badge.getAttribute('style') ?? '';
    expect(style).toContain('--accent-success-16');
    expect(style).toContain('--badge-fg: var(--text-success)');
    expect(style).not.toContain('--badge-fg: var(--accent-success)');
    expect(badge.textContent).toContain('Healthy');
  });
  it('projects the originator accent from the `origin` axis (originatorTone authority)', async () => {
    const agent = await mount<StatusBadge>('jf-status-badge', (c) => (c.origin = 'agent'));
    expect(agent.shadowRoot!.querySelector('.badge')!.getAttribute('style')).toContain(
      'var(--accent-command)',
    );
    const user = await mount<StatusBadge>('jf-status-badge', (c) => (c.origin = 'user'));
    expect(user.shadowRoot!.querySelector('.badge')!.getAttribute('style')).toContain(
      'var(--accent-tint)',
    );
  });
  it('gives the NEUTRAL case (origin system / status neutral) a delineating border; vivid tones none (§23.C.1)', async () => {
    const sys = await mount<StatusBadge>('jf-status-badge', (c) => (c.origin = 'system'));
    expect(sys.shadowRoot!.querySelector('.badge')!.getAttribute('style')).toContain(
      '--badge-border: var(--border-strong)',
    );
    const neutralStatus = await mount<StatusBadge>('jf-status-badge', (c) => (c.status = 'idle'));
    expect(neutralStatus.shadowRoot!.querySelector('.badge')!.getAttribute('style')).toContain(
      '--badge-border: var(--border-strong)',
    );
    const vivid = await mount<StatusBadge>('jf-status-badge', (c) => (c.status = 'completed'));
    expect(vivid.shadowRoot!.querySelector('.badge')!.getAttribute('style')).toContain(
      '--badge-border: transparent',
    );
  });
});

describe('jf-error-alert (574 atom)', () => {
  it('projects the tone onto the box border + tinted bg + text, and is an alert', async () => {
    const el = await mount<ErrorAlert>('jf-error-alert', (c) => (c.tone = 'error'));
    const box = el.shadowRoot!.querySelector('.alert') as HTMLElement;
    const style = box.getAttribute('style') ?? '';
    expect(style).toContain('var(--accent-danger)'); // --alert-fg (border + text)
    expect(style).toContain('--accent-danger-16'); // --alert-bg (tinted)
    expect(box.getAttribute('role')).toBe('alert');
  });
  it('renders the dismiss reactively when onDismiss is set, and fires it on activate', async () => {
    const onDismiss = vi.fn();
    const el = await mount<ErrorAlert>('jf-error-alert', () => {});
    expect(el.shadowRoot!.querySelector('jf-button')).toBeNull(); // no dismiss by default
    el.onDismiss = onDismiss;
    await el.updateComplete; // reactive property → re-render adds the dismiss
    const jfb = el.shadowRoot!.querySelector('jf-button');
    expect(jfb).toBeTruthy();
    await (jfb as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const control = jfb!.shadowRoot!.querySelector('jf-control');
    await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    (control!.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });
});
