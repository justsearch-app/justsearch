// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import './OpButton.js';
import { __seedForTest } from '../../api/registry/OperationCatalogClient.js';
import { __seedForTest as __seedResourceCatalog } from '../../i18n/resourceCatalog.js';
import type { OpButton } from './OpButton.js';
import type { ActionButton } from './ActionButton.js';
const properties = readFileSync('../app-api/src/main/resources/messages/registry-operation.en.properties', 'utf8');
const messages = Object.fromEntries(properties.split('\n').filter(line => line.includes('=')).map(line => {
  const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1).trim()];
}));
afterEach(() => { document.body.innerHTML = ''; });
it('L9 the served rebuild label is visible and names the catalog button; typed word stays rebuild', async () => {
  expect(messages['ops.rebuild-index.label']).toBe('Rebuild search index');
  expect(messages['ops.rebuild-index.confirm']).toBe('rebuild');
  __seedResourceCatalog(messages);
  __seedForTest({ schemaVersion:'1', catalogVersion:1, namespace:'core', primitive:'Operation', entries:[{
    id:'core.rebuild-index', policy:{risk:'HIGH',confirm:{kind:'TYPED',confirmTextKey:'ops.rebuild-index.confirm'}},
    presentation:{labelKey:'ops.rebuild-index.label',descriptionKey:'ops.rebuild-index.description'},
    lineage:{affects:[],supersedes:[]},
  }] } as never);
  const el=document.createElement('jf-op-button') as OpButton; el.operationId='core.rebuild-index';document.body.append(el);
  await el.updateComplete;
  const action=el.shadowRoot!.querySelector('jf-action-button') as ActionButton;await action.updateComplete;
  const button=action.shadowRoot!.querySelector('button')!;
  expect(button.textContent!.trim()).toBe('Rebuild search index');
  expect(button.getAttribute('aria-label'),'the visible text is the accessible name').toBeNull();
});
