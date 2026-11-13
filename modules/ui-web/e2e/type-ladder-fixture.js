// Check-only entry. Reuses the CSP fixture's real components, host, theme and AI pollers.
import { host, nav, mount, bootFrame } from './csp-fixture.js';
import '/src/shell-v0/views/search-v3/SearchV3View.ts';
import '/src/shell-v0/views/SystemSurface.ts';
import '/src/shell-v0/views/HealthSurface.ts';
import '/src/shell-v0/components/StatusPanel.ts';
import '/src/shell-v0/components/ConfirmDialog.ts';
import '/src/shell-v0/components/EngineRecovery.ts';
import '/src/shell-v0/components/chat/MarkdownBlock.ts';
import '/src/boot/factoryReset.ts';
import { resolveBootWithRecovery } from '/src/boot/desktopRecovery.ts';
import { __seedForTest } from '/src/api/registry/SurfaceCatalogClient.ts';

const entries = ['library', 'browse', 'system', 'health'].map(name => ({
  id: `core.${name}-surface`, mountTag: `jf-${name}-surface`,
  presentation: { labelKey: name, descriptionKey: '', iconHint: null, category: null },
  audience: 'USER', placement: 'DEEPLINK', altitude: 'PRODUCT',
  consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  ...(name === 'library' ? { members: ['core.browse-surface'] } : {}),
  ...(name === 'system' ? { members: ['core.health-surface'] } : {}),
}));
__seedForTest({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries });
const markdown = '# Heading one\n\n## Heading two\n\n### Heading three\n\n#### Heading four\n\n##### Heading five\n\n###### Heading six\n\nA cited sentence.\n\nRoman **strong** and *emphasis* with `inline code`.\n\n```js\nconst answer = 42;\n```\n\n| Name | Value |\n| --- | --- |\n| Example | 42 |';
for (const [name, tag] of [['Search', 'jf-sv3-window'], ['System/Health', 'jf-system-surface'],
  ['Status panel', 'jf-status-panel'], ['Confirm dialog', 'jf-confirm-dialog'],
  ['Factory reset', 'jf-factory-reset-recovery'], ['Engine recovery', 'jf-engine-recovery'],
  ['Markdown', 'jf-markdown-block']]) {
  const button = document.createElement('button'); button.textContent = name;
  button.onclick = () => {
    const el = document.createElement(tag); el.host_ = host; el.apiBase = '';
    if (name === 'Confirm dialog') Object.assign(el, { open: true, title: 'Remove this folder?', message: 'Files on disk will be kept.', confirmLabel: 'Remove folder' });
    if (name === 'Factory reset') el.status = { phase: 'incomplete', remaining: [{ path: '/fixture/cache', error: 'Fixture: storage unavailable' }] };
    if (name === 'Engine recovery') el.supervisor = { state: 'failed', reason: 'crash_loop', notReady: true };
    if (name === 'Markdown') Object.assign(el, { text: markdown, format: 'markdown', prose: true,
      citations: [{ sentenceText: 'A cited sentence.', sentenceIndex: 0, similarity: 0.95, label: 1,
        detail: { docId: '/csp-root/note.md', path: '/csp-root/note.md' },
        hover: { excerpt: 'A cited sentence.', title: 'note.md', headingText: 'Heading one' } }] });
    mount.replaceChildren(el);
  };
  nav.append(button);
}
const connection = document.createElement('button'); connection.textContent = 'Connection error';
// Restore the original frame so desktopRecovery owns the real browser boot-failure path.
nav.dataset.ladderControls = 'true';
connection.onclick = async () => {
  const root = document.getElementById('root');
  root.replaceChildren(bootFrame);
  await resolveBootWithRecovery(root);
};
nav.append(connection);
nav.querySelectorAll('button')[3].click();
document.body.dataset.typeLadderReady = 'true';
