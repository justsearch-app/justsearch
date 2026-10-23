// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';

const key = '0194f72c-0000-7000-8000-000000000001';
const message = 'The rebuild or migration stopped before activating the new index and settings; this request did not replace the existing index, so review settings and index health before retrying activation. (Code: ACTIVATION_PRECOMMIT_REFUSED)';
const failure = { state: 'failed', historySince: 0, message, reason: 'ACTIVATION_PRECOMMIT_REFUSED', result: { code: 'ACTIVATION_PRECOMMIT_REFUSED' } };
function feed(migrationState: string) {
  __feedForTest({ status: { worker: { core: { indexHealthy: true, indexState: 'IDLE' },
    migration: { migrationState, buildingGenerationId: migrationState === 'IDLE' ? null : `g-${key}` },
  } } as unknown as StatusSnapshot });
}
async function pump(el: LibrarySurface) {
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
}
afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); vi.restoreAllMocks(); });

it.each(['rebuild', 'cancel', 'accept-gaps'] as const)('Library shows the terminal %s outcome with its code', async (action) => {
  const code = action === 'cancel' ? 'cancelled' : 'ACTIVATION_PRECOMMIT_REFUSED';
  const sentence = action === 'cancel'
    ? 'The rebuild or migration was cancelled before activation; this request did not replace the existing index, and you can start a new rebuild when ready. (Code: cancelled)'
    : message;
  const terminal = { ...failure, message: sentence, reason: code, result: { code } };
  let outcome: object = action === 'accept-gaps'
    ? { state: 'running', historySince: 0, phase: 'awaiting_acceptance', result: {
      gapListHash: 'a'.repeat(64), gaps: [{ unitId: 'doc', reason: 'PARSER_FAILED' }] } }
    : { state: 'running', historySince: 0 };
  const invoke = vi.fn().mockImplementation(async () => { outcome = terminal; return { success: true }; });
  const fetch = vi.fn(async (path: string) => path === `/api/operation-history/${key}`
    ? new Response(JSON.stringify(outcome)) : new Response('{}', { status: 503 }));
  feed(action === 'accept-gaps' ? 'AWAITING_ACCEPTANCE' : 'MIGRATING');
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = { platform: { capabilities: new Set() }, data: { fetch, invokeOperation: invoke },
    ui: { showConfirmDialog: vi.fn().mockResolvedValue(true) },
  } as unknown as PluginHostApi;
  document.body.append(el); await pump(el);
  if (action === 'rebuild') {
    outcome = terminal;
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 4_001);
    feed('IDLE');
  } else {
    const label = action === 'cancel' ? 'Cancel rebuild' : 'Accept gaps and activate';
    const button = [...el.shadowRoot!.querySelectorAll('jf-button')].find(b => b.getAttribute('label') === label);
    expect(button).toBeDefined();
    (button as HTMLElement & { onActivate(): void }).onActivate();
  }
  await pump(el);
  expect([...el.shadowRoot!.querySelectorAll('[role="alert"]')].map(el => el.textContent).join(' ')).toContain(sentence);
  expect(el.shadowRoot!.textContent?.split(`Code: ${code}`)).toHaveLength(2);
  expect(el.shadowRoot!.querySelector('jf-button[label="Cancel rebuild"]')).toBeNull();
  if (action !== 'rebuild') expect(invoke).toHaveBeenCalledWith(`core.${action === 'cancel' ? 'cancel-reindex' : 'accept-gaps'}`,
    expect.objectContaining({ reindexKey: key }), { consented: true });
  feed('IDLE'); await pump(el);
  expect(el.shadowRoot!.textContent).toContain(sentence);
});
