// @vitest-environment happy-dom

/**
 * Rendered operation-history stream regression.
 *
 * This drives the real ResourceView -> pooled EnvelopeStream -> subscription
 * strategy -> jf-table path with a controlled HTTP SSE response. The rows are
 * intentionally shaped like the operation-history catalog entry, including
 * operationKey as its declared identity field.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './ResourceView.js';
import { fetchSseHarness, flushStreamWork } from '../../test/fetchSse.js';
import '../renderers/resourceRegistryDefaults.js';
import type { ResourceView } from './ResourceView.js';
import type { Resource, ResourceCatalog } from '../../api/types/registry.js';
import {
  __resetForTest as resetResourceCatalog,
  __seedForTest as seedResourceCatalog,
} from '../../api/registry/ResourceCatalogClient.js';
import {
  __resetForTest as resetSchemaFetcher,
  __seedForTest as seedSchema,
} from '../../api/registry/schemaFetcher.js';
import {
  __poolSizeForTest,
  __resetEnvelopeStreamPoolForTest,
} from '../streaming/EnvelopeStreamPool.js';

const SCHEMA_URL = 'https://ssot.justsearch/v1/schemas/operation-history-entry.v1.json';

type OperationOutcome = 'SUCCESS' | 'FAILURE' | 'UNDONE';

let streams: ReturnType<typeof fetchSseHarness>;
function operationHistoryResource(): Resource {
  return {
    id: 'core.operation-history',
    presentation: {
      labelKey: 'registry-resource.operation-history.label',
      descriptionKey: 'registry-resource.operation-history.description',
      iconHint: null,
      category: null,
    },
    schema: SCHEMA_URL,
    category: 'EVENT_STREAM',
    subscriptionMode: 'SSE_STREAM',
    endpoint: '/api/operation-history/stream',
    kind: 'operation-history',
    history: null,
    recovery: null,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: false, resolver: null },
    itemOperations: [],
    collectionOperations: [],
    primaryKey: 'operationKey',
    audience: 'USER',
    consumers: [],
    role: 'PRODUCT',
  };
}

function catalogOf(...entries: Resource[]): ResourceCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Resource',
    entries,
  };
}

function entry(operationKey: string | null, outcome: OperationOutcome): Record<string, unknown> {
  return {
    actor: 'user',
    diagnosticsLink: null,
    endTime: '2026-09-13T10:00:01Z',
    executionId: null,
    operationId: 'core.same-operation',
    operationKey,
    outcome,
    provenance: {
      correlationId: null,
      executor: 'UI',
      initiator: null,
      occurredAt: '2026-09-13T10:00:01Z',
      signedIntentToken: null,
      transport: 'BUTTON',
    },
    startTime: '2026-09-13T10:00:00Z',
  };
}

function envelope(seq: number, frameKind: 'LIFECYCLE' | 'UPDATE', payload: unknown) {
  return {
    streamId: 'surface:operation-history',
    frameKind,
    seq,
    ts: '2026-09-13T10:00:01Z',
    payload,
    resumeToken: `operation-history-token-${seq}`,
  };
}

async function settle(el: ResourceView): Promise<void> {
  await el.updateComplete;
  await flushStreamWork();
  const table = el.shadowRoot?.querySelector('jf-table') as
    | (HTMLElement & { updateComplete?: Promise<unknown> })
    | null;
  if (table?.updateComplete) await table.updateComplete;
  await el.updateComplete;
}

describe('Bulk rebuild refusal history rendering', () => {
  beforeEach(() => {
    resetResourceCatalog();
    resetSchemaFetcher();
    streams = fetchSseHarness();
    vi.stubGlobal('fetch', streams.fetch);
    seedSchema(SCHEMA_URL, { type: 'object', properties: {} });
    seedResourceCatalog(catalogOf(operationHistoryResource()));
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetEnvelopeStreamPoolForTest();
    resetResourceCatalog();
    resetSchemaFetcher();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('shows the sentence and keeps the support code through snapshot and live history', async () => {
    const el = document.createElement('jf-resource-view') as ResourceView;
    el.resourceId = 'core.operation-history';
    el.apiBase = 'http://127.0.0.1:33221';
    document.body.appendChild(el);
    await settle(el);
    const source = streams.connections[0]!;
    await source.open();
    const failure = { ...entry('019940c0-0000-7000-8000-000000000001', 'FAILURE'),
      operationId: 'core.bulk-reindex', diagnosticsLink: 'BULK_GENERATION_REFUSED',
      message: 'The rebuild or migration could not safely start or resume the new index; check index health and current settings before starting a new rebuild. (Code: BULK_GENERATION_REFUSED)' };
    await source.emitFrame(envelope(1, 'LIFECYCLE', { kind: 'snapshot', entries: [failure] }));
    await settle(el);
    let row = el.shadowRoot!.querySelector('jf-table')!.shadowRoot!.querySelector('.row')!;
    expect(row.textContent).toContain(failure.message);
    expect(row.textContent?.split('Code: BULK_GENERATION_REFUSED')).toHaveLength(2);
    expect(row.querySelector('[title="[object Object]"]')).toBeNull();
    await source.emitFrame(envelope(2, 'UPDATE', { ...failure,
      diagnosticsLink: 'BULK_CAPTURE_FAILED', message: 'The rebuild or migration could not finish collecting files; this request did not replace the existing index, so check source folder availability before starting a new rebuild. (Code: BULK_CAPTURE_FAILED)' }));
    await settle(el);
    row = el.shadowRoot!.querySelector('jf-table')!.shadowRoot!.querySelector('.row')!;
    expect(row.textContent).toContain('could not finish collecting files');
    expect(row.textContent?.split('Code: BULK_CAPTURE_FAILED')).toHaveLength(2);
    expect(row.textContent).not.toContain('BULK_GENERATION_REFUSED');
  });
});
