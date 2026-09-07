// @vitest-environment happy-dom

/**
 * Tempdoc 559 Authority V — Control primitive tests. Pins the operability the
 * primitive guarantees by construction: a native button (keyboard + role free),
 * an accessible name projected from the declaration (operationId via present()
 * or an explicit label), and the onActivate action.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './Control.js';
import type { Control } from './Control.js';

async function mount(setup: (el: Control) => void): Promise<Control> {
  const el = document.createElement('jf-control') as Control;
  setup(el);
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const innerButton = (el: Control) => el.shadowRoot?.querySelector('button') as HTMLButtonElement;

describe('Control (jf-control)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders a native <button> (keyboard + role for free)', async () => {
    const el = await mount((c) => (c.label = 'Do it'));
    const btn = innerButton(el);
    expect(btn).not.toBeNull();
    expect(btn.tagName).toBe('BUTTON');
    expect(btn.getAttribute('part')).toBe('control');
    expect(btn.type).toBe('button');
  });

  it('projects the accessible name from an explicit label', async () => {
    const el = await mount((c) => (c.label = 'Revert non-core overrides'));
    expect(innerButton(el).getAttribute('aria-label')).toBe('Revert non-core overrides');
  });

  /**
   * Tempdoc 840 — the toggle-button arm. TRI-STATE by design: unset must emit NO `aria-pressed`, so
   * the ~40 existing plain-command consumers keep rendering exactly as before (a `aria-pressed="false"`
   * on a plain button would tell AT it is an un-pressed toggle, which it is not).
   */
  it('emits no aria-pressed at all unless `pressed` is set', async () => {
    const el = await mount((c) => (c.label = 'Do it'));
    expect(innerButton(el).hasAttribute('aria-pressed')).toBe(false);
  });

  it('emits aria-pressed in both directions once `pressed` is set', async () => {
    const el = await mount((c) => {
      c.label = 'Search reranker';
      c.pressed = true;
    });
    expect(innerButton(el).getAttribute('aria-pressed')).toBe('true');
    el.pressed = false;
    await el.updateComplete;
    expect(innerButton(el).getAttribute('aria-pressed')).toBe('false');
    // …and the name stays put across the flip: the state is on the button, not in the label.
    expect(innerButton(el).getAttribute('aria-label')).toBe('Search reranker');
  });

  it('fires onActivate on click', async () => {
    const onActivate = vi.fn();
    const el = await mount((c) => {
      c.label = 'Go';
      c.onActivate = onActivate;
    });
    innerButton(el).click();
    expect(onActivate).toHaveBeenCalledTimes(1);
  });

  it('does not fire when disabled', async () => {
    const onActivate = vi.fn();
    const el = await mount((c) => {
      c.label = 'Go';
      c.disabled = true;
      c.onActivate = onActivate;
    });
    expect(innerButton(el).disabled).toBe(true);
    // a disabled native button does not dispatch click
    innerButton(el).click();
    expect(onActivate).not.toHaveBeenCalled();
  });

  it('renders slotted content', async () => {
    const el = document.createElement('jf-control') as Control;
    el.label = 'x';
    el.textContent = 'visible text';
    document.body.appendChild(el);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('slot')).not.toBeNull();
    expect(el.textContent).toContain('visible text');
  });

  // Tempdoc 596 — the typed-availability mode (additive; `disabled` above is unchanged).
  describe('availability (tempdoc 596)', () => {
    const TOAST_EVENT = 'jf-advisory-ephemeral';

    it('available → operable, no aria-disabled, fires onActivate', async () => {
      const onActivate = vi.fn();
      const el = await mount((c) => {
        c.label = 'Go';
        c.availability = { kind: 'available' };
        c.onActivate = onActivate;
      });
      const btn = innerButton(el);
      expect(btn.disabled).toBe(false);
      expect(btn.getAttribute('aria-disabled')).toBeNull();
      btn.click();
      expect(onActivate).toHaveBeenCalledTimes(1);
    });

    it('blocked → native disabled (inert), no aria-disabled', async () => {
      const onActivate = vi.fn();
      const el = await mount((c) => {
        c.label = 'Approve';
        c.availability = { kind: 'blocked' };
        c.onActivate = onActivate;
      });
      const btn = innerButton(el);
      expect(btn.disabled).toBe(true);
      expect(btn.getAttribute('aria-disabled')).toBeNull();
      btn.click();
      expect(onActivate).not.toHaveBeenCalled();
    });

    it('unavailable → aria-disabled + focusable + reachable reason via aria-describedby', async () => {
      const el = await mount((c) => {
        c.label = 'Agent';
        c.availability = { kind: 'unavailable', reason: 'AI is offline' };
      });
      const btn = innerButton(el);
      // NOT native-disabled → stays focusable (the §11.2 focus-reachability fix).
      expect(btn.disabled).toBe(false);
      expect(btn.getAttribute('aria-disabled')).toBe('true');
      btn.focus();
      expect(el.shadowRoot?.activeElement).toBe(btn);
      // The reason is reachable: aria-describedby resolves to a span carrying the reason text.
      const describedBy = btn.getAttribute('aria-describedby');
      expect(describedBy).toBeTruthy();
      const reasonEl = el.shadowRoot?.getElementById(describedBy as string);
      expect(reasonEl?.textContent).toBe('AI is offline');
      // Tempdoc 596 §9.4 — the reason is a VISIBLE-capable tooltip (role=tooltip), not the AT-only
      // visually-hidden span, and is a host SIBLING of the button (so the button's aria-disabled
      // opacity does not dim it). It is shown on hover/focus via CSS (opacity), verified live.
      expect(reasonEl?.getAttribute('role')).toBe('tooltip');
      expect(reasonEl?.classList.contains('visually-hidden')).toBe(false);
      expect(reasonEl?.parentElement).not.toBe(btn); // sibling of the button, not a child
    });

    it('unavailable activation → surfaces the reason (toast), does NOT call onActivate', async () => {
      const onActivate = vi.fn();
      const seen: string[] = [];
      const listener = (e: Event) => seen.push((e as CustomEvent).detail?.message);
      document.addEventListener(TOAST_EVENT, listener);
      const el = await mount((c) => {
        c.label = 'Documents';
        c.availability = { kind: 'unavailable', reason: 'No documents indexed yet' };
        c.onActivate = onActivate;
      });
      innerButton(el).click();
      document.removeEventListener(TOAST_EVENT, listener);
      expect(onActivate).not.toHaveBeenCalled(); // not a silent no-op, but not the action either
      expect(seen).toContain('No documents indexed yet');
    });

    it('degraded → OPERABLE (fires onActivate, not aria-disabled) but caveat is reachable', async () => {
      const onActivate = vi.fn();
      const el = await mount((c) => {
        c.label = 'Search documents';
        c.availability = { kind: 'degraded', caveat: 'Ranking is reduced' };
        c.onActivate = onActivate;
      });
      const btn = innerButton(el);
      // Degraded never blocks: no native-disabled, no aria-disabled, and the action fires.
      expect(btn.disabled).toBe(false);
      expect(btn.getAttribute('aria-disabled')).toBeNull();
      btn.click();
      expect(onActivate).toHaveBeenCalledTimes(1);
      // …but the caveat is reachable on the same tooltip (aria-describedby → role=tooltip span).
      const describedBy = btn.getAttribute('aria-describedby');
      expect(describedBy).toBeTruthy();
      const reasonEl = el.shadowRoot?.getElementById(describedBy as string);
      expect(reasonEl?.textContent).toBe('Ranking is reduced');
      expect(reasonEl?.getAttribute('role')).toBe('tooltip');
    });

    // Tempdoc 596 §16.5 — queue-and-auto-run for TRANSIENT unavailability.
    describe('queue-and-auto-run (transient)', () => {
      function captureToasts(): { seen: { message: string; severity?: string }[]; stop: () => void } {
        const seen: { message: string; severity?: string }[] = [];
        const listener = (e: Event) => {
          const d = (e as CustomEvent).detail;
          seen.push({ message: d?.message, severity: d?.severity });
        };
        document.addEventListener(TOAST_EVENT, listener);
        return { seen, stop: () => document.removeEventListener(TOAST_EVENT, listener) };
      }

      it('transient click → queues (no onActivate yet), then auto-fires when it becomes available', async () => {
        const onActivate = vi.fn();
        const { seen, stop } = captureToasts();
        const el = await mount((c) => {
          c.label = 'Agent';
          c.availability = { kind: 'unavailable', reason: 'Still starting', transient: true };
          c.onActivate = onActivate;
        });
        innerButton(el).click();
        expect(onActivate).not.toHaveBeenCalled();
        expect(seen.some((t) => /queued/i.test(t.message))).toBe(true);

        // Becomes operable → the held intent fires exactly once, with a success toast.
        el.availability = { kind: 'available' };
        await el.updateComplete;
        stop();
        expect(onActivate).toHaveBeenCalledTimes(1);
        expect(seen.some((t) => t.severity === 'success')).toBe(true);
      });

      it('settles to a NON-transient block before becoming ready → drops the queue (no fire), honest rollback', async () => {
        const onActivate = vi.fn();
        const { seen, stop } = captureToasts();
        const el = await mount((c) => {
          c.label = 'Agent';
          c.availability = { kind: 'unavailable', reason: 'Still starting', transient: true };
          c.onActivate = onActivate;
        });
        innerButton(el).click(); // queue

        el.availability = { kind: 'unavailable', reason: 'The local AI model is offline' };
        await el.updateComplete;
        stop();
        expect(onActivate).not.toHaveBeenCalled();
        expect(seen.some((t) => /couldn.t run/i.test(t.message))).toBe(true);
      });

      it('a SETTLED (non-transient) block never queues — it just surfaces the reason', async () => {
        const onActivate = vi.fn();
        const { seen, stop } = captureToasts();
        const el = await mount((c) => {
          c.label = 'Agent';
          c.availability = { kind: 'unavailable', reason: 'The local AI model is offline' };
          c.onActivate = onActivate;
        });
        innerButton(el).click();
        // Now make it available — a non-queued control must NOT auto-fire.
        el.availability = { kind: 'available' };
        await el.updateComplete;
        stop();
        expect(onActivate).not.toHaveBeenCalled();
        expect(seen.some((t) => /queued/i.test(t.message))).toBe(false);
      });
    });

    it('availability supersedes the legacy disabled boolean', async () => {
      const onActivate = vi.fn();
      const el = await mount((c) => {
        c.label = 'Go';
        c.disabled = true; // legacy flag…
        c.availability = { kind: 'available' }; // …overridden by availability
        c.onActivate = onActivate;
      });
      expect(innerButton(el).disabled).toBe(false);
      innerButton(el).click();
      expect(onActivate).toHaveBeenCalledTimes(1);
    });
  });

  // Tempdoc 608 — command acknowledgement: promise-aware activation lights an in-flight `busy` overlay
  // (spinner + `aria-disabled` + `aria-busy`, focus-preserving) until the returned command promise settles.
  // The overlay is the acknowledgement AND the re-entrancy guard, lives outside the 596 transient queue, and
  // is gated by spin-delay timing. These STATE-MACHINE tests set the thresholds to 0 to show busy
  // immediately; the timing sub-describe covers the delay/min-visible gating.
  describe('command acknowledgement (tempdoc 608)', () => {
    const TOAST_EVENT = 'jf-advisory-ephemeral';

    function deferred(): { promise: Promise<void>; resolve: () => void; reject: () => void } {
      let resolve!: () => void;
      let reject!: () => void;
      const promise = new Promise<void>((res, rej) => {
        resolve = res;
        reject = rej;
      });
      return { promise, resolve, reject };
    }

    // Mount with spin-delay disabled (busy shows immediately) — isolates the state machine from timing.
    const mountImmediate = (setup: (c: Control) => void) =>
      mount((c) => {
        c.showDelayMs = 0;
        c.minVisibleMs = 0;
        setup(c);
      });

    it('a void-returning onActivate never lights busy (strictly additive)', async () => {
      const el = await mountImmediate((c) => {
        c.label = 'Go';
        c.onActivate = () => {
          /* sync, returns undefined */
        };
      });
      innerButton(el).click();
      await el.updateComplete;
      expect(innerButton(el).getAttribute('aria-busy')).toBeNull();
      expect(innerButton(el).getAttribute('aria-disabled')).toBeNull();
      expect(el.shadowRoot?.querySelector('svg')).toBeNull();
    });

    it('a promise-returning onActivate lights busy: aria-busy + aria-disabled (focusable) + spinner', async () => {
      const d = deferred();
      const el = await mountImmediate((c) => {
        c.label = 'Add';
        c.onActivate = () => d.promise;
      });
      innerButton(el).click();
      await el.updateComplete;
      const btn = innerButton(el);
      expect(btn.getAttribute('aria-busy')).toBe('true');
      // Focus-preserving: aria-disabled (NOT native disabled) — the loading-button a11y pattern.
      expect(btn.getAttribute('aria-disabled')).toBe('true');
      expect(btn.disabled).toBe(false);
      expect(el.shadowRoot?.querySelector('svg')).not.toBeNull(); // the loader-2 spinner
      // The polite status region announces the in-progress state (WCAG 2.1 SC 4.1.3).
      const status = el.shadowRoot?.querySelector('[role="status"]');
      expect(status?.getAttribute('aria-live')).toBe('polite');
      expect(status?.textContent?.trim()).toBe('Working…');
      d.resolve();
    });

    it('busy clears when the command promise RESOLVES (status region empties)', async () => {
      const d = deferred();
      const el = await mountImmediate((c) => {
        c.label = 'Add';
        c.onActivate = () => d.promise;
      });
      innerButton(el).click();
      await el.updateComplete;
      expect(innerButton(el).getAttribute('aria-busy')).toBe('true');
      d.resolve();
      await d.promise;
      await el.updateComplete;
      expect(innerButton(el).getAttribute('aria-busy')).toBeNull();
      expect(innerButton(el).getAttribute('aria-disabled')).toBeNull();
      expect(el.shadowRoot?.querySelector('[role="status"]')?.textContent?.trim()).toBe('');
    });

    it('busy clears when the command promise REJECTS (failure is honest, not stuck)', async () => {
      const d = deferred();
      const el = await mountImmediate((c) => {
        c.label = 'Add';
        c.onActivate = () => d.promise;
      });
      innerButton(el).click();
      await el.updateComplete;
      expect(innerButton(el).getAttribute('aria-busy')).toBe('true');
      d.reject();
      await d.promise.catch(() => {});
      await el.updateComplete;
      expect(innerButton(el).getAttribute('aria-busy')).toBeNull();
      expect(innerButton(el).getAttribute('aria-disabled')).toBeNull();
    });

    it('re-entrancy: a second activation while busy does NOT re-fire the command', async () => {
      const d = deferred();
      const onActivate = vi.fn(() => d.promise);
      const el = await mountImmediate((c) => {
        c.label = 'Add';
        c.onActivate = onActivate;
      });
      innerButton(el).click(); // fires once → busy
      await el.updateComplete;
      expect(onActivate).toHaveBeenCalledTimes(1);
      // The button is aria-disabled (still clickable) — so this genuinely exercises the busy guard in
      // activate(), not native-disabled click suppression. A second click must not re-dispatch.
      expect(innerButton(el).disabled).toBe(false);
      innerButton(el).click();
      await el.updateComplete;
      expect(onActivate).toHaveBeenCalledTimes(1);
      d.resolve();
    });

    it('busy does NOT arm the transient queue (it is not an availability)', async () => {
      const seen: string[] = [];
      const listener = (e: Event) => seen.push((e as CustomEvent).detail?.message);
      document.addEventListener(TOAST_EVENT, listener);
      const d = deferred();
      const el = await mountImmediate((c) => {
        c.label = 'Add';
        c.availability = { kind: 'available' };
        c.onActivate = () => d.promise;
      });
      innerButton(el).click();
      await el.updateComplete;
      d.resolve();
      await d.promise;
      await el.updateComplete;
      document.removeEventListener(TOAST_EVENT, listener);
      // No "queued" / "Ran your queued action" toast — busy is the activation overlay, not a transient block.
      expect(seen.some((m) => /queued/i.test(m))).toBe(false);
    });

    // Spin-delay timing: the two-threshold anti-flicker (delay-before-show + min-visible). Fake timers fake
    // setTimeout (and Date); promise settle stays real, advanced+flushed by advanceTimersByTimeAsync.
    describe('spin-delay timing', () => {
      beforeEach(() => vi.useFakeTimers());
      afterEach(() => vi.useRealTimers());

      async function mountTimed(onActivate: () => Promise<void>): Promise<Control> {
        const el = document.createElement('jf-control') as Control;
        el.label = 'Add';
        el.showDelayMs = 200;
        el.minVisibleMs = 500;
        el.onActivate = onActivate;
        document.body.appendChild(el);
        await el.updateComplete;
        return el;
      }

      it('command settling BEFORE showDelayMs never shows busy (no flash)', async () => {
        const d = deferred();
        const el = await mountTimed(() => d.promise);
        innerButton(el).click();
        d.resolve(); // settles almost immediately, well under 200ms
        await vi.advanceTimersByTimeAsync(50);
        await el.updateComplete;
        expect(el.busy).toBe(false);
        expect(innerButton(el).getAttribute('aria-busy')).toBeNull();
        // Advance past where the spinner WOULD have shown — it must still never appear.
        await vi.advanceTimersByTimeAsync(300);
        await el.updateComplete;
        expect(el.busy).toBe(false);
        expect(el.shadowRoot?.querySelector('svg')).toBeNull();
      });

      it('command pending PAST showDelayMs shows busy, then holds it for minVisibleMs', async () => {
        const d = deferred();
        const el = await mountTimed(() => d.promise);
        innerButton(el).click();
        // Before the delay elapses: nothing shown yet.
        await vi.advanceTimersByTimeAsync(150);
        await el.updateComplete;
        expect(el.busy).toBe(false);
        // Cross the delay → busy shows.
        await vi.advanceTimersByTimeAsync(60);
        await el.updateComplete;
        expect(el.busy).toBe(true);
        // Settle right after it showed → the overlay is HELD for the minimum, not yanked away (no flicker).
        d.resolve();
        await d.promise;
        await vi.advanceTimersByTimeAsync(100); // < minVisible remaining
        await el.updateComplete;
        expect(el.busy).toBe(true);
        await vi.advanceTimersByTimeAsync(500); // past min-visible
        await el.updateComplete;
        expect(el.busy).toBe(false);
      });

      it('re-entrancy holds DURING the spin-delay window (busy not yet shown)', async () => {
        const d = deferred();
        const onActivate = vi.fn(() => d.promise);
        const el = document.createElement('jf-control') as Control;
        el.label = 'Add';
        el.showDelayMs = 200;
        el.minVisibleMs = 500;
        el.onActivate = onActivate;
        document.body.appendChild(el);
        await el.updateComplete;
        innerButton(el).click(); // command in flight, but busy not shown yet (pre-delay)
        await el.updateComplete;
        expect(el.busy).toBe(false); // overlay hasn't appeared
        // A second click in this window must NOT re-dispatch — the guard keys on in-flight, not on `busy`.
        innerButton(el).click();
        await el.updateComplete;
        expect(onActivate).toHaveBeenCalledTimes(1);
        d.resolve();
        await vi.advanceTimersByTimeAsync(700);
      });
    });
  });
});

/**
 * Tempdoc 859 (live console, 2026-08-25) — `[jf-control] no accessible name` fired on EVERY Search v3
 * and unified-chat session, in six independent Playwright captures, while axe found nothing. Both were
 * right: nothing was actually nameless, and the selfcheck was measuring a half-built element.
 *
 * It ran inside `render()`, so it saw whatever one frame happened to hold. Two shapes tripped it,
 * both reproduced by instrumenting the suite: a `<jf-control>` rendering before its own `label`
 * binding and slot text had been committed, and `jf-button`'s inner control, whose name arrives
 * through the OUTER slot — its only light child is a `<slot>` element, whose `textContent` is empty
 * however loud the button reads.
 *
 * A dev signal that cries wolf on every session is worse than none: it gets filtered, and then the
 * real nameless control it exists to catch arrives in the same noise. So the check is deferred past
 * the render that scheduled it and reads the name the way the flat tree does. These cases pin both
 * directions — the false positives are gone AND a genuinely nameless control still reports.
 */
describe('Control name selfcheck (tempdoc 859)', () => {
  /** The check runs on a macrotask; this is the turn it fires in. */
  const afterCheck = async (el: Control): Promise<void> => {
    await el.updateComplete;
    await new Promise<void>((r) => setTimeout(r, 0));
    await new Promise<void>((r) => setTimeout(r, 0));
  };

  type ErrorSpy = { mock: { calls: unknown[][] }; mockRestore: () => void };

  const nameErrors = (spy: ErrorSpy): unknown[][] =>
    spy.mock.calls.filter((args) => String(args[0] ?? '').includes('[jf-control]'));

  let errorSpy: ErrorSpy;

  beforeEach(() => {
    document.body.innerHTML = '';
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {}) as unknown as ErrorSpy;
  });

  afterEach(() => {
    errorSpy.mockRestore();
  });

  it('a name that arrives AFTER the first render is not reported', async () => {
    // The live shape: the element renders once with nothing committed, then its binding lands.
    const el = document.createElement('jf-control') as Control;
    document.body.appendChild(el);
    await el.updateComplete; // first render — nameless at this instant
    el.label = 'Add folders';
    await afterCheck(el);
    expect(innerButton(el).getAttribute('aria-label')).toBe('Add folders');
    expect(nameErrors(errorSpy)).toEqual([]);
  });

  it('a name projected THROUGH an outer slot is not reported (the jf-button shape)', async () => {
    // `<jf-button>Save</jf-button>` renders `<jf-control><slot></slot></jf-control>`: the inner
    // control's own light DOM is one `<slot>` element and its `textContent` is ''. The name is real
    // and an AT computes it from the flattened assignment, which is what the check now reads.
    const host = document.createElement('div');
    document.body.appendChild(host);
    const root = host.attachShadow({ mode: 'open' });
    root.innerHTML = '<jf-control><slot></slot></jf-control>';
    host.append(document.createTextNode('Save'));
    const el = root.querySelector('jf-control') as Control;
    expect((el.textContent ?? '').trim(), 'the naive read this check used to make').toBe('');
    await afterCheck(el);
    expect(nameErrors(errorSpy)).toEqual([]);
  });

  it('plain light-DOM slot text is not reported', async () => {
    const el = document.createElement('jf-control') as Control;
    el.append(document.createTextNode('Continue'));
    document.body.appendChild(el);
    await afterCheck(el);
    expect(nameErrors(errorSpy)).toEqual([]);
  });

  it('a control that is STILL nameless once the DOM has settled is reported', async () => {
    // The defect the signal exists for. If this ever stops firing, the deferral has swallowed the
    // check rather than fixed its timing.
    const el = document.createElement('jf-control') as Control;
    document.body.appendChild(el);
    await afterCheck(el);
    expect(nameErrors(errorSpy)).toHaveLength(1);
    expect(String(nameErrors(errorSpy)[0]?.[0])).toContain('no accessible name');
  });

  it('reports once per control, not once per render', async () => {
    const el = document.createElement('jf-control') as Control;
    document.body.appendChild(el);
    await afterCheck(el);
    el.requestUpdate();
    await afterCheck(el);
    el.requestUpdate();
    await afterCheck(el);
    // Three renders, and the control was nameless throughout — but a per-render shout is exactly the
    // console flood the live capture recorded, so re-arming is bounded to one pending check at a time.
    expect(nameErrors(errorSpy).length).toBeLessThanOrEqual(3);
    expect(nameErrors(errorSpy).length).toBeGreaterThanOrEqual(1);
  });

  it('a control torn down before it settles says nothing', async () => {
    const el = document.createElement('jf-control') as Control;
    document.body.appendChild(el);
    await el.updateComplete;
    el.remove();
    await new Promise<void>((r) => setTimeout(r, 0));
    await new Promise<void>((r) => setTimeout(r, 0));
    expect(nameErrors(errorSpy)).toEqual([]);
  });
});
