// SPDX-License-Identifier: Apache-2.0
/**
 * Visual-verification demo for the V0 Shell (slice 3a.1 Phase 6).
 *
 * Mounted by `main.jsx` when the URL carries `?shell-demo=1`. The
 * demo bypasses the React App and renders the Lit shell directly
 * into `#root`, with a small set of representative panes covering
 * Form, StatusCard, ActionButton, and the layout's docking +
 * save/restore.
 *
 * Run with:
 *   cd modules/ui-web && npm run dev
 *   # Open http://localhost:<port>/?shell-demo=1
 */

import { Shell } from '../shell/Shell.js';
// Side-effect imports for the components used in the demo:
import '../components/Form.js';
import '../components/StatusCard.js';
import '../components/ActionButton.js';
import '../components/Table.js';
import '../renderers/controls/TextControl.js';
import '../renderers/controls/NumberControl.js';
import '../renderers/controls/BooleanControl.js';
import '../renderers/layouts/VerticalLayout.js';
import '../themes/default.css';
import type { Form } from '../components/Form.js';
import type { Table } from '../components/Table.js';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';

export function mountShellDemo(host: HTMLElement): Shell {
  // Make the host (and the html/body chain it lives in) fill the
  // viewport so the dock panel has measurable dimensions. Without
  // this, Lumino's DockPanel computes zero size and collapses
  // (becomes hidden in Playwright's `visible` check).
  document.documentElement.style.height = '100%';
  document.body.style.height = '100%';
  document.body.style.margin = '0';
  host.style.position = 'absolute';
  host.style.inset = '0';
  host.style.padding = '0';
  host.style.margin = '0';

  const shell = new Shell();

  // Pane 1: a Form using the slice-3a.0 renderer set.
  const formPane = document.createElement('jf-form') as Form;
  const formSchema: JsonSchema = {
    type: 'object',
    properties: {
      name: { type: 'string', title: 'Name' },
      age: { type: 'integer', title: 'Age' },
      newsletter: { type: 'boolean', title: 'Subscribe to newsletter' },
    },
  };
  const formUischema: UISchemaElement = {
    type: 'VerticalLayout',
    elements: [
      { type: 'Control', scope: '#/properties/name' },
      { type: 'Control', scope: '#/properties/age' },
      { type: 'Control', scope: '#/properties/newsletter' },
    ],
  } as UISchemaElement;
  formPane.schema = formSchema;
  formPane.uischema = formUischema;
  formPane.data = { name: 'Ada Lovelace', age: 36, newsletter: true };
  formPane.style.padding = '1rem';
  shell.addPane({ id: 'demo-form', title: 'Form', content: formPane });

  // Pane 2: a StatusCard at WARNING severity.
  const status = document.createElement('jf-status-card');
  status.setAttribute('severity', 'WARNING');
  status.setAttribute('subject', 'WorkerHandshake');
  status.setAttribute('reason', 'WorkerOffline');
  status.setAttribute(
    'details',
    'index component did not respond within 5s. Last seen 2 minutes ago.',
  );
  const statusWrap = document.createElement('div');
  statusWrap.style.padding = '1rem';
  statusWrap.appendChild(status);
  shell.addPane({
    id: 'demo-status',
    title: 'Status',
    content: statusWrap,
  });

  // Pane 3: an ActionButton at HIGH risk.
  const action = document.createElement('jf-action-button');
  action.setAttribute('operation-id', 'index.reset');
  action.setAttribute('label', 'Reset Index');
  action.setAttribute('risk', 'HIGH');
  const actionWrap = document.createElement('div');
  actionWrap.style.padding = '1rem';
  actionWrap.appendChild(action);
  action.addEventListener('action-invoke', (e) => {
    const detail = (e as CustomEvent).detail as {
      operationId: string;
      risk: string;
    };
    console.log('[demo] action-invoke fired', detail);
  });
  shell.addPane({
    id: 'demo-action',
    title: 'Action',
    content: actionWrap,
  });

  // Pane 4: a Table with schema-derived columns.
  type DemoRow = { name: string; role: string; commits: number };
  const tableSchema: JsonSchema = {
    type: 'array',
    items: {
      type: 'object',
      properties: {
        name: { type: 'string', title: 'Name' },
        role: { type: 'string', title: 'Role' },
        commits: { type: 'integer', title: 'Commits' },
      },
    },
  };
  const tableData: DemoRow[] = [
    { name: 'Ada Lovelace', role: 'Mathematician', commits: 142 },
    { name: 'Grace Hopper', role: 'Engineer', commits: 387 },
    { name: 'Margaret Hamilton', role: 'Engineer', commits: 256 },
    { name: 'Katherine Johnson', role: 'Mathematician', commits: 89 },
    { name: 'Hedy Lamarr', role: 'Inventor', commits: 53 },
  ];
  const table = document.createElement('jf-table') as Table<DemoRow>;
  table.schema = tableSchema;
  table.data = tableData;
  table.style.height = '100%';
  shell.addPane({ id: 'demo-table', title: 'Table', content: table });

  // Pane 5: a save/restore control panel.
  const controls = document.createElement('div');
  controls.style.padding = '1rem';
  controls.style.fontFamily = 'system-ui, sans-serif';
  controls.innerHTML = `
    <h3 style="margin-top:0">Layout controls</h3>
    <p>Drag tabs between panes to dock. Use the buttons below to
       persist the layout.</p>
    <button id="demo-save">Save layout</button>
    <button id="demo-restore">Restore layout</button>
    <button id="demo-clear">Clear saved</button>
    <pre id="demo-layout-preview" style="font-size: var(--font-size-xs); max-height:240px; overflow:auto; background:#f4f4f4; padding:0.5rem; margin-top:1rem;"></pre>
  `;
  shell.addPane({
    id: 'demo-controls',
    title: 'Controls',
    content: controls,
    closable: false,
  });

  shell.attachTo(host);

  const preview = controls.querySelector(
    '#demo-layout-preview',
  ) as HTMLPreElement;
  const refreshPreview = () => {
    preview.textContent = JSON.stringify(shell.saveLayout(), null, 2);
  };
  refreshPreview();

  controls.querySelector('#demo-save')?.addEventListener('click', () => {
    const json = JSON.stringify(shell.saveLayout());
    localStorage.setItem('jf-shell-demo-layout', json);
    refreshPreview();
  });
  controls.querySelector('#demo-restore')?.addEventListener('click', () => {
    const stored = localStorage.getItem('jf-shell-demo-layout');
    if (!stored) {
      alert('No saved layout. Click Save first.');
      return;
    }
    shell.restoreLayout(JSON.parse(stored));
    refreshPreview();
  });
  controls.querySelector('#demo-clear')?.addEventListener('click', () => {
    localStorage.removeItem('jf-shell-demo-layout');
    refreshPreview();
  });

  return shell;
}
