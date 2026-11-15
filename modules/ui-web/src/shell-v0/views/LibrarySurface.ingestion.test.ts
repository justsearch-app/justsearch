// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { IngestionSummary } from '../components/IngestionSummary.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';

afterEach(() => { document.body.replaceChildren(); __resetAiStateForTest(); });

it('Library Folders mounts the real summary consumer and tears it down when Browse is selected', async () => {
  let summarySignal: AbortSignal | undefined;
  const fetch = vi.fn(async (path: string, init?: { signal?: AbortSignal }) => {
    if (path.startsWith('/api/diagnostics/ingestion/summary')) {
      summarySignal = init?.signal;
      return new Promise<Response>(() => {});
    }
    return new Response('', { status: 503 });
  });
  const library = document.createElement('jf-library-surface') as LibrarySurface;
  library.host_ = { platform: { capabilities: new Set<string>() }, data: { fetch } } as unknown as PluginHostApi;
  document.body.append(library);
  await library.updateComplete;
  expect(library.shadowRoot!.querySelector('jf-ingestion-summary')).toBeNull();
  const activity = library.shadowRoot!.querySelector<HTMLElement & { onActivate: () => void }>('[label="Activity"]')!;
  activity.onActivate(); await library.updateComplete;
  const panel = library.shadowRoot!.querySelector<IngestionSummary>('jf-ingestion-summary');
  expect(panel).not.toBeNull();
  await panel!.updateComplete;
  expect(panel!.shadowRoot!.textContent).toContain('Loading reading activity');
  expect(summarySignal).toBeDefined();
  library.activeTab = 'core.browse-surface';
  await library.updateComplete;
  expect(library.shadowRoot!.querySelector('jf-ingestion-summary')).toBeNull();
  expect(summarySignal!.aborted).toBe(true);
  library.activeTab = 'folders';
  await library.updateComplete;
  expect(library.shadowRoot!.querySelector('jf-ingestion-summary')).not.toBeNull();
  expect(fetch.mock.calls.filter(([path]) => path.startsWith('/api/diagnostics/ingestion/summary'))).toHaveLength(2);
});
