// @vitest-environment happy-dom

/**
 * Tempdoc 806 W1 (round-12 finding R12-F3) — the Memory surface must not make claims a locked store
 * cannot support.
 *
 * Three pins, one per lie the surface used to tell:
 *  1. a locked read renders the LOCKED state, never "No learned memory yet." (an empty list from an
 *     unreadable store is "cannot read", not "nothing learned");
 *  2. a refused mutation (423) renders as a failure, not as success — pre-806 both `remember()` and
 *     `forget()` awaited the fetch and never looked at its status, so a write that never landed
 *     re-rendered as if it had, and a forget that never happened made the fact vanish from screen;
 *  3. the remember input is disabled with a reason while locked (direction D5 — prevent, don't report).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './MemorySurface.js';
import type { MemorySurface } from './MemorySurface.js';
import { reasonFor } from '../state/readinessNotice.js';

const EMPTY_STATE = 'No learned memory yet.';

interface FetchStep {
  readonly status: number;
  readonly body: unknown;
}

/** Queue of scripted responses; each fetch consumes the next (last one repeats). */
function scriptFetch(steps: FetchStep[]): { calls: string[] } {
  const calls: string[] = [];
  const queue = [...steps];
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push(`${init?.method ?? 'GET'} ${String(input)}`);
    const step = (queue.length > 1 ? queue.shift() : queue[0]) as FetchStep;
    return Promise.resolve(
      new Response(JSON.stringify(step.body), {
        status: step.status,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  });
  return { calls };
}

async function mount(): Promise<MemorySurface> {
  const el = document.createElement('jf-memory-surface') as MemorySurface;
  document.body.appendChild(el);
  await settle(el);
  return el;
}

async function settle(el: MemorySurface): Promise<void> {
  // Let the in-flight fetch chain resolve, then wait for the render it triggered.
  for (let i = 0; i < 6; i++) await Promise.resolve();
  await el.updateComplete;
  for (let i = 0; i < 6; i++) await Promise.resolve();
  await el.updateComplete;
}

function text(el: MemorySurface): string {
  return el.shadowRoot?.textContent ?? '';
}

describe('MemorySurface — 806 W1 locked-state truthfulness', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows unavailable on first open and revisit, then reloads after recovery", async () => {
    const fact = {
      id: "m1",
      kind: "fact",
      content: "all memories recovered",
      actor: "chat",
      createdAt: "2026-01-01T00:00:00Z",
    };
    scriptFetch([
      { status: 503, body: { errorCode: "MEMORIES_UNAVAILABLE" } },
      { status: 503, body: { errorCode: "MEMORIES_UNAVAILABLE" } },
      { status: 200, body: { memories: [fact], locked: false } },
    ]);
    const el = await mount();
    expect(text(el)).toContain("Memories are unavailable");
    expect(text(el)).not.toContain(EMPTY_STATE);
    expect(
      (el.shadowRoot?.querySelector("input.draft") as HTMLInputElement)
        .disabled,
    ).toBe(true);
    el.remove();
    document.body.appendChild(el);
    await settle(el);
    expect(text(el)).toContain("Memories are unavailable");
    expect(text(el)).not.toContain(EMPTY_STATE);
    el.remove();
    document.body.appendChild(el);
    await settle(el);
    expect(text(el)).toContain(fact.content);
    expect(text(el)).not.toContain("Memories are unavailable");
  });

  it("hides a stale list when a revisit fails to load", async () => {
    scriptFetch([
      {
        status: 200,
        body: { locked: false, memories: [{ id: "old", content: "stale fact" }] },
      },
      { status: 503, body: {} },
    ]);
    const el = await mount();
    expect(text(el)).toContain("stale fact");
    el.remove();
    document.body.appendChild(el);
    await settle(el);
    expect(text(el)).toContain("Memories are unavailable");
    expect(text(el)).not.toContain("stale fact");
    expect(text(el)).not.toContain(EMPTY_STATE);
  });

  it('renders the LOCKED state and never the empty state when the read reports locked', async () => {
    scriptFetch([{ status: 200, body: { memories: [], locked: true } }]);
    const el = await mount();

    expect(text(el)).not.toContain(EMPTY_STATE);
    // Worded from the ONE CAUSE_ROWS authority, so it cannot drift from the locked-chat affordance.
    expect(text(el)).toContain(reasonFor('memory.locked').wording);
    expect(text(el)).toContain('Unlock to see what the AI has learned');
  });

  it('still shows the empty state when the store is readable and genuinely empty', async () => {
    scriptFetch([{ status: 200, body: { memories: [], locked: false } }]);
    const el = await mount();

    expect(text(el)).toContain(EMPTY_STATE);
    expect(text(el)).not.toContain(reasonFor('memory.locked').wording);
  });

  it('disables the remember input with a reason while locked (D5 — prevent, do not report)', async () => {
    scriptFetch([{ status: 200, body: { memories: [], locked: true } }]);
    const el = await mount();

    const input = el.shadowRoot?.querySelector('input.draft') as HTMLInputElement;
    expect(input.disabled).toBe(true);
    // The reason is VISIBLE (a `title` is unreachable on a disabled control — 596 §1.1).
    expect(input.placeholder).toContain('Unlock');

    // The button carries the same reason as typed availability (aria-disabled + a reachable reason),
    // so a blocked activation says why instead of silently doing nothing.
    const button = el.shadowRoot?.querySelector('jf-button[label="Remember"]') as unknown as {
      availability?: { kind: string; reason: string };
    };
    expect(button.availability?.kind).toBe('unavailable');
    expect(button.availability?.reason).toContain('Unlock');
  });

  it('a 423 remember does NOT render as success: the draft survives and the failure is shown', async () => {
    scriptFetch([
      { status: 200, body: { memories: [], locked: false } }, // initial list
      { status: 423, body: { errorCode: 'STORE_LOCKED', locked: true } }, // the refused POST
      { status: 200, body: { memories: [], locked: true } }, // the re-read
    ]);
    const el = await mount();

    el.rememberDraft = 'the fact that never landed';
    await (el as unknown as { remember(): Promise<void> }).remember();
    await settle(el);

    expect(el.rememberDraft).toBe('the fact that never landed'); // unsaved text is not thrown away
    expect(text(el)).toContain(reasonFor('memory.locked').wording);
    expect(text(el)).toContain('nothing was saved');
    expect(text(el)).not.toContain(EMPTY_STATE);
  });

  it('a 423 forget does NOT make the fact vanish: it stays listed and the failure is shown', async () => {
    const fact = {
      id: 'm1',
      kind: 'fact',
      content: 'the fact the user wants gone',
      actor: 'primary',
      createdAt: '2026-04-03T00:00:00Z',
    };
    scriptFetch([
      { status: 200, body: { memories: [fact], locked: false } }, // initial list
      { status: 423, body: { errorCode: 'STORE_LOCKED', locked: true } }, // the refused DELETE
      { status: 200, body: { memories: [fact], locked: false } }, // the re-read: still there
    ]);
    const el = await mount();
    expect(text(el)).toContain(fact.content);

    await (el as unknown as { forget(id: string): Promise<void> }).forget('m1');
    await settle(el);

    // Pre-806 the status was ignored, so a 423 was followed by a re-list that (while locked) came back
    // empty — the fact appeared deleted and silently returned on unlock.
    expect(text(el)).toContain(fact.content);
    expect(text(el)).toContain('nothing was forgotten');
  });
});
