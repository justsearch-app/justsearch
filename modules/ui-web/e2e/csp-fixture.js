// Production-build fixture: real components, catalogs, theme capability and AI pollers.
// No component source or style API is replaced. Backend responses are mocked by Playwright.
import '/src/styles/tokens.css';
import '/src/shell-v0/themes/default.css';
import '/src/shell-v0/views/LibrarySurface.ts';
import '/src/shell-v0/views/BrowseSurface.ts';
import '/src/shell-v0/views/BrainSurface.ts';
import '/src/shell-v0/views/SettingsSurface.ts';
import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
import { createThemeApi } from '/src/shell-v0/plugin-api/capabilities/theme.ts';
import { __seedForTest } from '/src/api/registry/SurfaceCatalogClient.ts';
import { startAiStateStore } from '/src/shell-v0/state/aiStateStore.ts';
const surfaces = ['library', 'browse'].map(name => ({
  id: `core.${name}-surface`, mountTag: `jf-${name}-surface`,
  presentation: { labelKey: name, descriptionKey: '', iconHint: null, category: null },
  audience: 'USER', placement: 'DEEPLINK', altitude: 'PRODUCT',
  consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  ...(name === 'library' ? { members: ['core.browse-surface'] } : {}),
}));
__seedForTest({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: surfaces });
const theme = createThemeApi('CORE', 'csp-verifier');
theme.saveTheme({ id: 'custom.csp-proof', displayName: 'CSP proof', tokens: { 'h-teal': '120' } });
export const host = createMockHostApi({ theme, data: { fetch: (url, init) => fetch(url, init) } });
const root = document.getElementById('root');
export const bootFrame = document.getElementById('boot-frame');
root.replaceChildren();
export const nav = document.createElement('nav');
export const mount = document.createElement('div');
root.append(nav, mount);
for (const [name, tag] of [['Library', 'jf-library-surface'], ['AI Brain', 'jf-brain-surface'], ['Settings', 'jf-settings-surface']]) {
  const button = document.createElement('button'); button.textContent = name;
  button.onclick = () => {
    const element = document.createElement(tag); element.host_ = host; element.apiBase = '';
    if (name === 'Settings') element.activeCategory = 'appearance';
    mount.replaceChildren(element);
  };
  nav.append(button);
}
startAiStateStore('');
nav.firstElementChild.click();
document.body.dataset.cspReady = 'true';
