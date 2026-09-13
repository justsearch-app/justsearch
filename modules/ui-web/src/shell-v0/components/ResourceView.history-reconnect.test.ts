// @vitest-environment happy-dom

/**
 * Rendered operation-history stream regression.
 *
 * This drives the real ResourceView -> pooled EnvelopeStream -> subscription
 * strategy -> jf-table path with a controllable EventSource. The rows are
 * intentionally shaped like the operation-history catalog entry, including
 * operationKey as its declared identity field.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './ResourceView.js';
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

class ControlledEventSource extends EventTarget {
  static instances: ControlledEventSource[] = [];

  readonly url: string;
  readonly listenerTypes = new Set<string>();
  readyState = 0;
  readonly CONNECTING = 0;
  readonly OPEN = 1;
  readonly CLOSED = 2;
  closed = false;
  withCredentials = false;

  constructor(url: string | URL) {
    super();
    this.url = String(url);
    ControlledEventSource.instances.push(this);
  }

  override addEventListener(
    type: string,
    callback: EventListenerOrEventListenerObject | null,
    options?: boolean | AddEventListenerOptions,
  ): void {
    this.listenerTypes.add(type);
    super.addEventListener(type, callback, options);
  }

  override removeEventListener(
    type: string,
    callback: EventListenerOrEventListenerObject | null,
    options?: boolean | EventListenerOptions,
  ): void {
    super.removeEventListener(type, callback, options);
    this.listenerTypes.delete(type);
  }

  emitOpen(): void {
    this.readyState = this.OPEN;
    this.dispatchEvent(new Event('open'));
  }

  emitFrame(envelope: object): void {
    this.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify(envelope) }));
  }

  close(): void {
    this.readyState = this.CLOSED;
    this.closed = true;
  }
}

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
  const table = el.shadowRoot?.querySelector('jf-table') as
    | (HTMLElement & { updateComplete?: Promise<unknown> })
    | null;
  if (table?.updateComplete) await table.updateComplete;
  await el.updateComplete;
}

function outcomeCells(el: ResourceView): string[] {
  const table = el.shadowRoot?.querySelector('jf-table');
  return Array.from(table?.shadowRoot?.querySelectorAll('.row') ?? []).map(
    (row) => row.querySelectorAll('.cell')[1]?.textContent?.trim() ?? '',
  );
}

describe('ResourceView operation-history snapshot overlap rendering', () => {
  beforeEach(() => {
    resetResourceCatalog();
    resetSchemaFetcher();
    ControlledEventSource.instances = [];
    seedSchema(SCHEMA_URL, { type: 'object', properties: {} });
    seedResourceCatalog(catalogOf(operationHistoryResource()));
    vi.stubGlobal('EventSource', ControlledEventSource);
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetEnvelopeStreamPoolForTest();
    resetResourceCatalog();
    resetSchemaFetcher();
    vi.unstubAllGlobals();
  });

  it('renders keyed convergence and independent unkeyed observations through the real stream path', async () => {
    const el = document.createElement('jf-resource-view') as ResourceView;
    el.resourceId = 'core.operation-history';
    el.apiBase = 'http://127.0.0.1:33221';
    document.body.appendChild(el);
    await settle(el);

    const source = ControlledEventSource.instances[0];
    if (!source) throw new Error('ResourceView did not open its history stream');
    expect(source?.url).toBe('http://127.0.0.1:33221/api/operation-history/stream');
    expect(__poolSizeForTest()).toBe(1);
    expect(source?.listenerTypes).toEqual(new Set(['frame', 'open', 'error']));

    source.emitOpen();
    source.emitFrame(
      envelope(1, 'LIFECYCLE', {
        kind: 'snapshot',
        entries: [
          entry('019940c0-0000-7000-8000-000000000001', 'FAILURE'),
          entry('019940c0-0000-7000-8000-000000000002', 'FAILURE'),
          entry(null, 'UNDONE'),
        ],
      }),
    );
    await settle(el);

    expect(outcomeCells(el)).toEqual(['FAILURE', 'FAILURE', 'UNDONE']);

    // The same operationKey converges in place, while the same operationId
    // with a distinct operationKey remains a separate visible row.
    source.emitFrame(envelope(2, 'UPDATE', entry('019940c0-0000-7000-8000-000000000001', 'SUCCESS')));
    // A null declared key never aliases an earlier unkeyed observation.
    source.emitFrame(envelope(3, 'UPDATE', entry(null, 'SUCCESS')));
    await settle(el);

    expect(el.connectionState).toBe('connected');
    expect(outcomeCells(el)).toEqual(['SUCCESS', 'FAILURE', 'UNDONE', 'SUCCESS']);

    el.remove();
    expect(__poolSizeForTest()).toBe(0);
    expect(source.closed).toBe(true);
    expect(source.listenerTypes).toEqual(new Set());
  });
});
