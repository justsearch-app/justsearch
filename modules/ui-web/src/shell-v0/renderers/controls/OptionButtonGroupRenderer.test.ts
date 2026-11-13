// @vitest-environment happy-dom

/**
 * 569 Fix 1 — bespoke-quality renderers that let a DECLARED settings body render like the
 * hand-authored UI (so the declaration can be the DEFAULT with no visual downgrade).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './OptionButtonGroupRenderer.js';
import './ToggleSwitchRenderer.js';
import type { OptionButtonGroupRenderer } from './OptionButtonGroupRenderer.js';
import type { ToggleSwitchRenderer } from './ToggleSwitchRenderer.js';
import { getXUiRendererTag } from './XUiRendererControl.js';

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('<jf-option-button-group>', () => {
  it('registers hint option-button-group → jf-option-button-group', () => {
    expect(getXUiRendererTag('option-button-group')).toBe('jf-option-button-group');
  });

  it('renders one keyboard-operable <button> per enum value with the selected one marked', async () => {
    const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
    document.body.appendChild(el);
    el.schema = {
      type: 'string',
      enum: ['simple', 'advanced'],
      'x-enum-labels': { simple: 'Simple', advanced: 'Advanced' },
    } as OptionButtonGroupRenderer['schema'];
    el.uischema = { type: 'Control' };
    el.data = 'advanced';
    el.enabled = true;
    el.visible = true;
    el.path = 'mode';
    el.onChange = () => {};
    await el.updateComplete;
    const buttons = Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []);
    expect(buttons).toHaveLength(2);
    expect(buttons.map((b) => b.textContent?.trim())).toEqual(['Simple', 'Advanced']);
    const selected = el.shadowRoot?.querySelector('button.option-btn.selected');
    expect(selected?.getAttribute('aria-checked')).toBe('true');
    expect(selected?.textContent?.trim()).toBe('Advanced');
  });

  // Fix-round F1 — the declared-schema counterpart to plain-props swatch options. Before the fix,
  // the schema branch hardcoded `swatches = {}`, so `x-enum-swatches` was silently ignored and a
  // DECLARED theme picker (the default boot path) could never render swatch tiles at all.
  it('renders swatch tiles from the schema `x-enum-swatches` extension (declared path)', async () => {
    const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
    document.body.appendChild(el);
    el.schema = {
      type: 'string',
      enum: ['system', 'dark', 'light'],
      'x-enum-labels': { system: 'System', dark: 'Dark', light: 'Light' },
      'x-enum-swatches': {
        system: { split: ['#1a1a1e', '#f4f4f5'] },
        dark: { fill: '#1a1a1e' },
        light: { fill: '#f4f4f5' },
      },
    } as OptionButtonGroupRenderer['schema'];
    el.uischema = { type: 'Control' };
    el.data = 'dark';
    el.enabled = true;
    el.visible = true;
    el.path = 'theme';
    el.onChange = () => {};
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.option-group.swatch-group')).toBeTruthy();
    const tiles = el.shadowRoot?.querySelectorAll('.option-swatch-tile');
    expect(tiles?.length).toBe(3);
    const darkFill = tiles?.[1]?.firstElementChild as HTMLElement;
    expect(darkFill?.getAttribute('style')).toContain('background: #1a1a1e');
    const selected = el.shadowRoot?.querySelector('button.option-btn.selected');
    expect(selected?.querySelector('.option-swatch-check')).toBeTruthy();
  });

  it('emits onChange(value, path) on click', async () => {
    const calls: Array<{ value: unknown; path: string }> = [];
    const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
    document.body.appendChild(el);
    el.schema = { type: 'string', enum: ['simple', 'advanced'] } as OptionButtonGroupRenderer['schema'];
    el.uischema = { type: 'Control' };
    el.data = 'simple';
    el.enabled = true;
    el.visible = true;
    el.path = 'mode';
    el.onChange = (value, path) => calls.push({ value, path });
    await el.updateComplete;
    const buttons = el.shadowRoot?.querySelectorAll('button.option-btn');
    (buttons?.[1] as HTMLButtonElement).click();
    expect(calls).toEqual([{ value: 'advanced', path: 'mode' }]);
  });

  // Tempdoc 855 §15.2/§17 R2 — the WAI-ARIA radiogroup keyboard model, added once here.
  describe('keyboard model', () => {
    async function mountThree(current = 'a'): Promise<{
      el: OptionButtonGroupRenderer;
      calls: Array<{ value: unknown; path: string }>;
      buttons: () => HTMLButtonElement[];
    }> {
      const calls: Array<{ value: unknown; path: string }> = [];
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.schema = { type: 'string', enum: ['a', 'b', 'c'] } as OptionButtonGroupRenderer['schema'];
      el.uischema = { type: 'Control' };
      el.data = current;
      el.enabled = true;
      el.visible = true;
      el.path = 'mode';
      el.onChange = (value, path) => {
        calls.push({ value, path });
        el.data = value as string;
      };
      await el.updateComplete;
      return {
        el,
        calls,
        buttons: () => Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []) as HTMLButtonElement[],
      };
    }

    it('roving tabindex: only the selected button is a tab stop', async () => {
      const { buttons } = await mountThree('b');
      const tabindexes = buttons().map((b) => b.getAttribute('tabindex'));
      expect(tabindexes).toEqual(['-1', '0', '-1']);
    });

    it('ArrowRight moves selection to the next option and focuses it', async () => {
      const { el, calls, buttons } = await mountThree('a');
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'b', path: 'mode' }]);
      expect(buttons()[1]!).toBe(el.shadowRoot?.activeElement);
    });

    it('ArrowDown behaves like ArrowRight', async () => {
      const { el, calls, buttons } = await mountThree('a');
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'b', path: 'mode' }]);
    });

    it('ArrowLeft/Up moves selection to the previous option, wrapping at the start', async () => {
      const { el, calls, buttons } = await mountThree('a');
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'c', path: 'mode' }]);
    });

    it('ArrowRight wraps from the last option to the first', async () => {
      const { el, calls, buttons } = await mountThree('c');
      buttons()[2]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'a', path: 'mode' }]);
    });

    it('Home selects and focuses the first option', async () => {
      const { el, calls, buttons } = await mountThree('c');
      buttons()[2]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'a', path: 'mode' }]);
      expect(buttons()[0]!).toBe(el.shadowRoot?.activeElement);
    });

    it('End selects and focuses the last option', async () => {
      const { el, calls, buttons } = await mountThree('a');
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'c', path: 'mode' }]);
      expect(buttons()[2]!).toBe(el.shadowRoot?.activeElement);
    });

    it('an unrelated key is ignored', async () => {
      const { el, calls, buttons } = await mountThree('a');
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([]);
    });

    it('disabled group ignores keyboard navigation', async () => {
      const { el, calls, buttons } = await mountThree('a');
      el.enabled = false;
      await el.updateComplete;
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([]);
    });
  });

  // Tempdoc 855 §17 R2 — plain-props usage path (no JsonForms schema/onChange plumbing).
  describe('plain-props path', () => {
    const OPTIONS = [
      { value: 'simple', label: 'Simple', description: 'Standard view' },
      { value: 'advanced', label: 'Advanced' },
    ];

    it('renders from `options`/`value` instead of schema/data', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = OPTIONS;
      el.value = 'advanced';
      await el.updateComplete;
      const buttons = Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []);
      expect(buttons.map((b) => b.querySelector('.option-label')?.textContent?.trim())).toEqual([
        'Simple',
        'Advanced',
      ]);
      const selected = el.shadowRoot?.querySelector('button.option-btn.selected');
      expect(selected?.textContent?.trim()).toContain('Advanced');
      expect(
        el.shadowRoot?.querySelector('.option-desc')?.textContent?.trim(),
      ).toBe('Standard view');
    });

    it('emits CustomEvent("change", {detail:{value}}) on click, not onChange', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = OPTIONS;
      el.value = 'simple';
      const onChangeCalls: unknown[] = [];
      el.onChange = (v) => onChangeCalls.push(v);
      const changeCalls: Array<{ value: string }> = [];
      el.addEventListener('change', (e) => changeCalls.push((e as CustomEvent<{ value: string }>).detail));
      await el.updateComplete;
      const buttons = el.shadowRoot?.querySelectorAll('button.option-btn');
      (buttons?.[1] as HTMLButtonElement).click();
      expect(changeCalls).toEqual([{ value: 'advanced' }]);
      expect(onChangeCalls).toEqual([]);
    });

    it('keyboard model also drives the plain-props path', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = OPTIONS;
      el.value = 'simple';
      const changeCalls: Array<{ value: string }> = [];
      el.addEventListener('change', (e) => changeCalls.push((e as CustomEvent<{ value: string }>).detail));
      await el.updateComplete;
      const buttons = () => Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []) as HTMLButtonElement[];
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await el.updateComplete;
      expect(changeCalls).toEqual([{ value: 'advanced' }]);
    });
  });

  // Tempdoc 855 §15.2 — the swatch option shape (the Appearance System/Dark/Light trio's mechanism):
  // a per-option custom visual replacing the icon slot, reusing the SAME keyboard model + roving
  // tabindex rather than forking a second radiogroup component.
  // Fix-round F1 — `swatch` is the serializable `SwatchSpec` ({fill}|{split}), not a `TemplateResult`
  // (a JsonForms schema is DATA and cannot carry a TemplateResult; this is the one vocabulary both
  // the plain-props path and the declared `x-enum-swatches` schema extension consume).
  describe('swatch option shape', () => {
    const SWATCH_OPTIONS = [
      { value: 'system', label: 'System', swatch: { split: ['#1a1a1e', '#f4f4f5'] as [string, string] } },
      { value: 'dark', label: 'Dark', swatch: { fill: '#1a1a1e' } },
      { value: 'light', label: 'Light', swatch: { fill: '#f4f4f5' } },
    ];

    it('renders the swatch fill instead of an icon, and marks the group swatch-group', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = SWATCH_OPTIONS;
      el.value = 'dark';
      await el.updateComplete;
      expect(el.shadowRoot?.querySelector('.option-group.swatch-group')).toBeTruthy();
      const tiles = el.shadowRoot?.querySelectorAll('.option-swatch-tile');
      expect(tiles?.length).toBe(3);
      // `dark` is a flat {fill} spec — the rendered fill span's background is the literal color.
      const darkFill = tiles?.[1]?.firstElementChild as HTMLElement;
      expect(darkFill?.getAttribute('style')).toContain('background: #1a1a1e');
      // `system` is a {split} spec — rendered as a diagonal two-tone gradient.
      const systemFill = tiles?.[0]?.firstElementChild as HTMLElement;
      expect(systemFill?.getAttribute('style')).toContain('linear-gradient(135deg, #1a1a1e 50%, #f4f4f5 50%)');
    });

    it('applies the option-btn-swatch modifier class to every option', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = SWATCH_OPTIONS;
      el.value = 'system';
      await el.updateComplete;
      const buttons = Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []);
      expect(buttons.every((b) => b.classList.contains('option-btn-swatch'))).toBe(true);
    });

    it('renders a check badge only on the selected swatch tile', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = SWATCH_OPTIONS;
      el.value = 'light';
      await el.updateComplete;
      const buttons = Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []);
      const checks = buttons.map((b) => b.querySelector('.option-swatch-check'));
      expect(checks.filter(Boolean).length).toBe(1);
      const selectedBtn = el.shadowRoot?.querySelector('button.option-btn.selected');
      expect(selectedBtn?.querySelector('.option-swatch-check')).toBeTruthy();
    });

    it('the swatch trio still emits change on click and drives the SAME keyboard model', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = SWATCH_OPTIONS;
      el.value = 'system';
      const changeCalls: Array<{ value: string }> = [];
      el.addEventListener('change', (e) => changeCalls.push((e as CustomEvent<{ value: string }>).detail));
      await el.updateComplete;
      const buttons = () => Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []) as HTMLButtonElement[];
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await el.updateComplete;
      expect(changeCalls).toEqual([{ value: 'dark' }]);
    });
  });

  // Tempdoc 855 fix-round F2 (M1) — the `role="radiogroup"` accessible name. Two independent
  // sources (plain-props `groupLabel` vs. the JsonForms `schema.title`), matching how `options`
  // vs. `schema.enum` already fork the two modes.
  describe('radiogroup accessible name (fix-round F2 M1)', () => {
    it('plain-props: an unset groupLabel leaves the radiogroup without an aria-label (red-before-fix baseline)', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = [{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }];
      el.value = 'a';
      await el.updateComplete;
      expect(el.shadowRoot?.querySelector('[role="radiogroup"]')?.hasAttribute('aria-label')).toBe(false);
    });

    it('plain-props: groupLabel sets aria-label on the radiogroup', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = [{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }];
      el.value = 'a';
      el.groupLabel = 'Detail level';
      await el.updateComplete;
      expect(el.shadowRoot?.querySelector('[role="radiogroup"]')?.getAttribute('aria-label')).toBe(
        'Detail level',
      );
    });

    it('JsonForms path: schema.title sets aria-label on the radiogroup', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.schema = {
        type: 'string',
        title: 'Default result action',
        enum: ['open', 'reveal', 'preview'],
      } as OptionButtonGroupRenderer['schema'];
      el.uischema = { type: 'Control' };
      el.data = 'open';
      el.enabled = true;
      el.visible = true;
      el.path = 'defaultAction';
      el.onChange = () => {};
      await el.updateComplete;
      expect(el.shadowRoot?.querySelector('[role="radiogroup"]')?.getAttribute('aria-label')).toBe(
        'Default result action',
      );
    });

    it('JsonForms path: a schema with no title leaves the radiogroup without an aria-label', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.schema = { type: 'string', enum: ['a', 'b'] } as OptionButtonGroupRenderer['schema'];
      el.uischema = { type: 'Control' };
      el.data = 'a';
      el.enabled = true;
      el.visible = true;
      el.path = 'x';
      el.onChange = () => {};
      await el.updateComplete;
      expect(el.shadowRoot?.querySelector('[role="radiogroup"]')?.hasAttribute('aria-label')).toBe(false);
    });
  });

  // Tempdoc 855 fix-round F2 (M3) — a swatch tile's description used to render as visible text
  // inside the fixed 4rem tile (squashing it); it now demotes to `title` + a composed aria-label,
  // mirroring `SettingsSurface.renderThemeTile()`'s "name: description" idiom.
  describe('swatch description demotion (fix-round F2 M3)', () => {
    const SWATCH_WITH_DESC = [
      { value: 'dark', label: 'Dark', description: 'Default theme', swatch: { fill: '#1a1a1e' } },
      { value: 'light', label: 'Light', description: 'Bright theme', swatch: { fill: '#f4f4f5' } },
    ];

    it('does not render a visible .option-desc for a swatch option (red-before-fix: previously rendered)', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = SWATCH_WITH_DESC;
      el.value = 'dark';
      await el.updateComplete;
      expect(el.shadowRoot?.querySelectorAll('.option-desc').length).toBe(0);
    });

    it('composes the description into title + aria-label as "Label: description"', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = SWATCH_WITH_DESC;
      el.value = 'dark';
      await el.updateComplete;
      const buttons = Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []);
      expect(buttons[0]?.getAttribute('title')).toBe('Default theme');
      expect(buttons[0]?.getAttribute('aria-label')).toBe('Dark: Default theme');
      expect(buttons[1]?.getAttribute('title')).toBe('Bright theme');
      expect(buttons[1]?.getAttribute('aria-label')).toBe('Light: Bright theme');
    });

    it('a swatch option with no description carries neither title nor aria-label', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = [{ value: 'system', label: 'System', swatch: { split: ['#111', '#eee'] as [string, string] } }];
      el.value = 'system';
      await el.updateComplete;
      const btn = el.shadowRoot?.querySelector('button.option-btn');
      expect(btn?.hasAttribute('title')).toBe(false);
      expect(btn?.hasAttribute('aria-label')).toBe(false);
    });

    it('a non-swatch (grid) option KEEPS its visible description, unaffected by the demotion', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = [{ value: 'simple', label: 'Simple', description: 'Standard view' }];
      el.value = 'simple';
      await el.updateComplete;
      expect(el.shadowRoot?.querySelector('.option-desc')?.textContent?.trim()).toBe('Standard view');
      expect(el.shadowRoot?.querySelector('button.option-btn')?.hasAttribute('aria-label')).toBe(false);
    });
  });
});

describe('<jf-toggle-switch>', () => {
  it('registers hint toggle-switch → jf-toggle-switch', () => {
    expect(getXUiRendererTag('toggle-switch')).toBe('jf-toggle-switch');
  });

  it('renders a role=switch reflecting the boolean and toggles on click', async () => {
    const calls: Array<{ value: unknown; path: string }> = [];
    const el = document.createElement('jf-toggle-switch') as ToggleSwitchRenderer;
    document.body.appendChild(el);
    el.schema = { type: 'boolean', title: 'High contrast' } as ToggleSwitchRenderer['schema'];
    el.uischema = { type: 'Control' };
    el.data = false;
    el.enabled = true;
    el.visible = true;
    el.path = 'highContrast';
    el.onChange = (value, path) => calls.push({ value, path });
    await el.updateComplete;
    // Tempdoc 855 §15.2/§17 R2 — the switch itself is the shared `jf-switch` atom, composed inside
    // this renderer's own shadow root; `[role="switch"]` lives in jf-switch's shadow root (a separate
    // tree — shadow DOM does not compose across nested custom elements for querySelector).
    const jfSwitch = el.shadowRoot?.querySelector('jf-switch');
    const sw = jfSwitch?.shadowRoot?.querySelector('[role="switch"]') as HTMLElement;
    expect(sw.getAttribute('aria-checked')).toBe('false');
    expect(sw.getAttribute('tabindex')).toBe('0');
    sw.click();
    expect(calls).toEqual([{ value: true, path: 'highContrast' }]);
  });

  it('toggles on Space/Enter (keyboard-operable)', async () => {
    const calls: unknown[] = [];
    const el = document.createElement('jf-toggle-switch') as ToggleSwitchRenderer;
    document.body.appendChild(el);
    el.schema = { type: 'boolean', title: 'Vim' } as ToggleSwitchRenderer['schema'];
    el.uischema = { type: 'Control' };
    el.data = true;
    el.enabled = true;
    el.visible = true;
    el.path = 'vimMode';
    el.onChange = (value) => calls.push(value);
    await el.updateComplete;
    const jfSwitch = el.shadowRoot?.querySelector('jf-switch');
    const sw = jfSwitch?.shadowRoot?.querySelector('[role="switch"]') as HTMLElement;
    sw.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    expect(calls).toEqual([false]);
  });
});
