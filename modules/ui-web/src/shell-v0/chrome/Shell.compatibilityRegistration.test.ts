// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
// Deliberately import only the production entry: component imports would hide cold-registration loss.
import './Shell.js';
import { mountView, stampViewFactory } from '../router/view-factory.js';
import { getViewFactory, registerViewFactory, unregisterViewFactory } from '../router/viewFactoryRegistry.js';
import { getSurface, mergePluginSurfaceContributions, mountSurface, removePluginSurfaceContributions } from '../../api/registry/SurfaceCatalogClient.js';
import { dispatchRenderer, getRendererRegistry, registerRenderer } from '../renderers/dispatch.js';
import { getXUiRendererTag, registerXUiRenderer, unregisterXUiRenderer } from '../renderers/controls/xUiRendererRegistry.js';
import '../renderers/controls/XUiRendererControl.js';
import { isComponentTag } from '../renderers/component-vocabulary.generated.js';
import { presentationDeclarationJsonSchema } from '../themes/presentationSchema.js';
import { validatePresentationDeclaration } from '../themes/presentationDeclaration.js';

const publicTags = ['jf-route-chip', 'jf-scope-chip', 'jf-conversation-history',
  'jf-handoff-card', 'jf-agent-authority-panel', 'jf-autonomy-dial'];
const authoringTokens = ['jf-unified-chat-view', 'jf-selection-actions-menu', ...publicTags];

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } })));
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.body.replaceChildren();
  unregisterViewFactory('compat.plugin-shape', 'compat');
  removePluginSurfaceContributions('compat');
  unregisterXUiRenderer('compat');
  const registry = getRendererRegistry();
  const index = registry.findIndex(entry => entry.tag === 'jf-unified-chat-view');
  if (index >= 0) registry.splice(index, 1);
});

describe('SA15 cold production registration and authoring compatibility', () => {
  it('registers Search eagerly without defining the retired root', () => {
    expect(customElements.get('jf-sv3-window')).toBeDefined();
    expect(customElements.get('jf-unified-chat-view')).toBeUndefined();
  });
  it.each(publicTags)('registers %s before Settings loads, and a plugin factory constructs it', tag => {
    expect(customElements.get('jf-settings-surface')).toBeUndefined();
    expect(customElements.get(tag)).toBeDefined();
    expect(mountView(stampViewFactory('compat.plugin-shape', tag)).localName).toBe(tag);
  });

  it.each(authoringTokens)('keeps %s admitted through generated vocabulary, schema and runtime', component => {
    expect(isComponentTag(component)).toBe(true);
    expect(presentationDeclarationJsonSchema.properties.layout.properties.regions.items.properties.component.enum)
      .toContain(component);
    const declaration = { schemaVersion: 1, id: 'compat.skin', displayName: 'Compatibility',
      layout: { regions: [{ id: 'main', component }] } };
    expect(validatePresentationDeclaration(declaration).ok).toBe(true);
    expect(declaration.layout.regions[0]!.component).toBe(component);
  });

  it('canonicalizes a plugin surface at construction while retaining identity, provenance and brand', () => {
    const provenance = { tier: 'UNTRUSTED_PLUGIN' as const, contributorId: 'compat', version: '1' };
    mergePluginSurfaceContributions([{ pluginId: 'compat', effectiveAudience: 'USER', provenance,
      contribution: { id: 'compat.surface', mountTag: 'jf-unified-chat-view', labelKey: 'compat.label',
        descriptionKey: 'compat.description', audience: 'USER', placement: 'RAIL' } }]);
    const surface = getSurface('compat.surface')!;
    expect(surface.id).toBe('compat.surface');
    expect(surface.mountTag).toBe('jf-unified-chat-view');
    expect(surface.provenance).toEqual(provenance);
    expect(mountSurface(surface, { apiBase: '/compat' })?.localName).toBe('jf-sv3-window');
    expect(() => mountSurface({ ...surface, factory: { ...surface.factory! } })).toThrow(/VALID_FACTORIES/);
  });

  it('canonicalizes an owned shape factory without permitting a different owner to replace it', () => {
    const factory = registerViewFactory('compat.plugin-shape', 'jf-unified-chat-view', 'compat');
    registerViewFactory('compat.plugin-shape', 'jf-route-chip', 'other');
    expect(getViewFactory('compat.plugin-shape')).toBe(factory);
    expect(factory.shapeRef).toBe('compat.plugin-shape');
    expect(mountView(factory, { apiBase: '/compat' }).localName).toBe('jf-sv3-window');
    expect(() => mountView({ ...factory })).toThrow(/WeakSet/);
  });

  it('canonicalizes both ranked and override renderer dispatch results', () => {
    registerRenderer(() => 10000, 'jf-unified-chat-view');
    expect(dispatchRenderer({ type: 'Control' }, {})).toBe('jf-sv3-window');
    expect(dispatchRenderer({ type: 'Control' }, {}, {
      version: 1, rendererOverride: { 'renderer:': 'jf-unified-chat-view' },
    })).toBe('jf-sv3-window');
  });

  it('canonicalizes hints through the production XUiRendererControl', async () => {
    registerXUiRenderer('compat', 'jf-unified-chat-view');
    expect(getXUiRendererTag('compat')).toBe('jf-sv3-window');
    const control = document.createElement('jf-x-ui-renderer-control') as HTMLElement & {
      schema: object; visible: boolean; updateComplete: Promise<boolean>;
    };
    control.schema = { 'x-ui-renderer': 'compat' };
    control.visible = true;
    document.body.append(control);
    await control.updateComplete;
    expect(control.shadowRoot?.querySelector('jf-sv3-window')).not.toBeNull();
    expect(control.shadowRoot?.querySelector('jf-unified-chat-view')).toBeNull();
  });

  it('canonicalizes the factory-absent Stage fallback', async () => {
    const stage = document.createElement('jf-stage') as HTMLElement & {
      surface: unknown; updateComplete: Promise<boolean>;
    };
    stage.surface = { id: 'compat.fallback', mountTag: 'jf-unified-chat-view',
      presentation: { labelKey: 'compat.label', descriptionKey: 'compat.description' },
      audience: 'USER', placement: 'RAIL', provenance: { tier: 'CORE', contributorId: 'core', version: '1' } };
    document.body.append(stage);
    await stage.updateComplete;
    expect(stage.shadowRoot?.querySelector('jf-sv3-window')).not.toBeNull();
  });
});
