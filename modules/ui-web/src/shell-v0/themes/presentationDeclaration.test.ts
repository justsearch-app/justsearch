/**
 * Tests for the Presentation Declaration (569 Move 1 — one artifact; Move 2 — the
 * breaking forms are unrepresentable by construction at the format layer).
 */
import { describe, expect, it } from 'vitest';
import {
  validatePresentationDeclaration,
  toThemeTree,
  type PresentationDeclaration,
} from './presentationDeclaration.js';

const VALID: PresentationDeclaration = {
  schemaVersion: 1,
  id: 'user.my-skin',
  displayName: 'My Skin',
  theme: { tokens: { 'accent-tint': 'oklch(70% 0.1 200)', 'surface-1': '#222' } },
  body: {
    'core.settings': {
      schema: { type: 'object', properties: { theme: { type: 'string' } } },
      uischema: { type: 'VerticalLayout', elements: [] },
    },
  },
  layout: {
    regions: [
      { id: 'main', component: 'jf-declared-surface', order: 0 },
      { id: 'aside', order: 1, visibleWhen: 'data.advanced == true' },
    ],
  },
};

describe('PresentationDeclaration — validation', () => {
  it('rejects a skin that mounts native Engine recovery controls', () => {
    const recoveryLayout = {
      ...VALID,
      layout: { regions: [{ id: 'main', component: 'jf-engine-recovery', order: 0 }] },
    };
    expect(validatePresentationDeclaration(recoveryLayout).ok).toBe(false);
    expect(validatePresentationDeclaration(VALID).ok).toBe(true);
  });

  it('accepts a valid declaration across theme/body/layout tiers', () => {
    const r = validatePresentationDeclaration(VALID);
    expect(r.ok).toBe(true);
  });

  it('569 §14 — accepts a body with the co-projected liveness + overflow facets', () => {
    const r = validatePresentationDeclaration({
      ...VALID,
      body: {
        'core.settings': {
          schema: { type: 'object', properties: {} },
          uischema: { type: 'VerticalLayout', elements: [] },
          liveness: 'core.retrieval',
          overflow: [
            { id: 'a', label: 'Indexed', priority: 100, pinned: true },
            { id: 'b', label: 'Queue', priority: 50 },
          ],
        },
      },
    });
    expect(r.ok).toBe(true);
  });

  it('569 §14 — rejects a malformed overflow facet (the author declares ordered items only)', () => {
    const r = validatePresentationDeclaration({
      ...VALID,
      body: {
        'core.settings': {
          schema: { type: 'object', properties: {} },
          uischema: { type: 'VerticalLayout', elements: [] },
          liveness: 42 as unknown as string, // not a string id
          overflow: [{ id: 'a', label: 'x' }] as unknown as [], // missing numeric priority
        },
      },
    });
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.errors.join(' ')).toMatch(/liveness must be a string/);
      expect(r.errors.join(' ')).toMatch(/priority/);
    }
  });

  it('rejects an unknown theme token (closed token vocabulary)', () => {
    const r = validatePresentationDeclaration({
      ...VALID,
      theme: { tokens: { 'not-a-token': '#000' } },
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.join(' ')).toMatch(/not a known token/);
  });

  it('rejects a token value that could break out of :root (no raw CSS in values)', () => {
    const r = validatePresentationDeclaration({
      ...VALID,
      theme: { tokens: { 'accent-tint': 'red} html{display:none}' } },
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.join(' ')).toMatch(/brace/);
  });

  it('rejects a layout region mounting a component outside the closed vocabulary', () => {
    const r = validatePresentationDeclaration({
      ...VALID,
      layout: { regions: [{ id: 'x', component: 'evil-element' }] },
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.join(' ')).toMatch(/closed/);
  });

  it('MOVE 2: a cssText field is UNREPRESENTABLE — validation rejects it, not ignores it', () => {
    const withCss = { ...VALID, cssText: '*::before{content:"pwned"}' } as unknown;
    const r = validatePresentationDeclaration(withCss);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.join(' ')).toMatch(/no slot for it/);
  });

  it('projects the theme tier to a DesignTokenTree for the single apply writer', () => {
    const r = validatePresentationDeclaration(VALID);
    expect(r.ok).toBe(true);
    if (r.ok) {
      const tree = toThemeTree(r.declaration);
      expect(tree?.tokens['accent-tint']).toBe('oklch(70% 0.1 200)');
      expect(tree?.id).toBe('user.my-skin');
    }
  });

  it('toThemeTree returns null when there is no theme tier', () => {
    const r = validatePresentationDeclaration({
      schemaVersion: 1,
      id: 'user.layout-only',
      displayName: 'Layout Only',
      layout: { regions: [{ id: 'main' }] },
    });
    expect(r.ok).toBe(true);
    if (r.ok) expect(toThemeTree(r.declaration)).toBeNull();
  });

  it('MOVE 8: accepts a valid interaction (statechart) tier', () => {
    const r = validatePresentationDeclaration({
      ...VALID,
      interaction: {
        'confirm.delete': {
          id: 'confirm.delete',
          initial: 'idle',
          states: [
            { id: 'idle', transitions: [{ on: 'REQUEST', target: 'confirming' }] },
            {
              id: 'confirming',
              transitions: [
                { on: 'CONFIRM', target: 'idle', effects: [{ kind: 'invoke-operation', operationId: 'data.delete-all' }] },
              ],
            },
          ],
        },
      },
    });
    expect(r.ok).toBe(true);
  });

  it('MOVE 8: rejects an interaction whose transition fires an arbitrary-code effect', () => {
    const r = validatePresentationDeclaration({
      ...VALID,
      interaction: {
        evil: {
          id: 'evil',
          initial: 'a',
          states: [{ id: 'a', transitions: [{ on: 'GO', effects: [{ kind: 'eval', code: 'steal()' }] }] }],
        },
      },
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.join(' ')).toMatch(/not an AUTHORABLE effect/);
  });
});
