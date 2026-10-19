// @vitest-environment happy-dom

/**
 * Tempdoc 586 P-3 — regression guard for the Help "Keyboard shortcuts" table.
 *
 * The previous table advertised shortcuts that don't fire: `/` (focus search) and
 * `/` (command mode) were unimplemented V1 deferrals, and `??` is a search-box query
 * prefix, not a keycap. P-3 rewrote the module-private `SHORTCUTS` array in
 * HelpSurface to only the bindings that actually fire. `SHORTCUTS` is private and
 * renders either through the projection engine (`renderReferenceRegion`) or the
 * built-in fallback (`renderShortcuts`), so this asserts the rendered output across
 * the surface's full (nested) shadow tree rather than the const directly.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetForTest as resetOperationCatalog,
  __seedForTest as seedOperationCatalog,
} from '../../api/registry/OperationCatalogClient.js';
import type { Operation, OperationCatalog } from '../../api/types/registry.js';
import {
  __resetJournalForTest,
  exportJournalArchive,
  listJournal,
} from '../substrates/effects/index.js';
import { bootstrapAggregateSubstrate, __resetBootstrap } from '../aggregate-substrate/bootstrap.js';
import { __clearAggregateRegistry } from '../aggregate-substrate/aggregateRegistry.js';
import './HelpSurface.js';
import type { OpSuccessEventDetail } from '../components/OpButton.js';

/** Concatenate text across the element's whole shadow subtree (nested shadow roots included). */
function collectShadowText(host: Element): string {
  let text = '';
  const queue: Element[] = [host];
  let i = 0;
  while (i < queue.length && i < 5000) {
    const node = queue[i++];
    if (!node) continue;
    const sr = node.shadowRoot;
    if (sr) {
      text += ' ' + (sr.textContent ?? '');
      queue.push(...Array.from(sr.children));
    }
    queue.push(...Array.from(node.children));
  }
  return text.replace(/\s+/g, ' ');
}

interface HelpElement extends HTMLElement {
  apiBase: string;
  copyText: (text: string) => Promise<boolean>;
  updateComplete: Promise<unknown>;
}

function summaryDefinition(): Operation {
  return {
    id: 'core.copy-diagnostic-summary',
    presentation: {
      labelKey: 'registry-operation.copy-diagnostic-summary.label',
      descriptionKey: 'registry-operation.copy-diagnostic-summary.description',
      iconHint: null,
      category: null,
    },
    intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
    policy: {
      risk: 'LOW',
      confirm: { kind: 'NONE' },
      audit: 'METADATA_ONLY',
      undoSupported: false,
    },
    availability: {},
    lineage: { affects: [], supersedes: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    executors: ['UI'],
    audience: 'USER',
    consumers: [],
  };
}

function summaryCatalog(): OperationCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Operation',
    entries: [summaryDefinition()],
  };
}

function storageText(): string {
  const values: string[] = [];
  for (let i = 0; i < localStorage.length; i += 1) {
    const key = localStorage.key(i);
    if (key !== null) values.push(localStorage.getItem(key) ?? '');
  }
  return values.join('\n');
}

async function mountHelp(): Promise<HelpElement> {
  const el = document.createElement('jf-help-surface') as HelpElement;
  el.apiBase = '';
  document.body.appendChild(el);
  // Let the surface + any nested declared-surface / renderers settle.
  await el.updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  await el.updateComplete;
  return el;
}

function summaryOperation(el: HelpElement): HTMLElement {
  const operation = el.shadowRoot?.querySelector<HTMLElement>(
    'jf-operation[operation-id="core.copy-diagnostic-summary"]',
  );
  if (!operation) throw new Error('diagnostic summary operation not rendered');
  return operation;
}

async function dispatchSummarySuccess(el: HelpElement, summary: string): Promise<void> {
  summaryOperation(el).dispatchEvent(new CustomEvent<OpSuccessEventDetail>('op-success', {
    bubbles: true,
    composed: true,
    detail: {
      message: 'backend message must not be rendered',
      executionId: 'exec-test',
      structuredData: { summary },
    },
  }));
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
}

describe('HelpSurface — keyboard shortcuts (tempdoc 586 P-3)', () => {
  afterEach(() => {
    document.querySelectorAll('jf-help-surface').forEach((el) => el.remove());
  });

  it('lists only shortcuts that actually fire; the defunct `/` and `??` rows are gone', async () => {
    const el = await mountHelp();
    const text = collectShadowText(el);

    // The corrected, real bindings are present.
    expect(text).toContain('Ctrl / ⌘ + K');
    expect(text).toContain('Open the command palette');

    // Tempdoc 857 PR-A — J/K fires (a window listener on the transcript region) and was missing from
    // the table, which makes the same mirror partial in the other direction. P-3's rule is that the
    // table mirrors the REAL bindings; this asserts the newly-listed one is actually rendered, since
    // the suite reads rendered substrings rather than the module-private const.
    expect(text).toContain('J / K');
    expect(text).toContain('Step to the next / previous step of a run');

    // The misleading / unimplemented rows P-3 removed must NOT reappear.
    expect(text).not.toContain('Focus search bar');
    expect(text).not.toContain('Enter command mode');
    expect(text).not.toContain('Enter AI chat mode');

    // The chords handled outside the registry (Shell.ts handleGlobalKey) and the search-bar keys
    // (Composer.ts) are listed; Esc does not clear the search bar (Composer.ts has no Escape branch).
    expect(text).toContain('Ctrl / ⌘ + L or /');
    expect(text).toContain('Alt + ← / →');
    expect(text).toContain('Ctrl + D');
    expect(text).toContain('Ctrl + Shift + A');
    expect(text).toContain('Ctrl + Enter');
    expect(text).not.toContain('Clear the search box');
  });

  it('troubleshooting names only operations the Library and Health views render', async () => {
    const el = await mountHelp();
    const text = collectShadowText(el);
    expect(text).not.toContain('Restart worker');
    expect(text).not.toContain('Library (Detailed)');
    expect(text).toContain('Recover component');
    expect(text).toContain('Force Rebuild');
  });

  it('FAQ no longer teaches the retired `??` prefix', async () => {
    const el = await mountHelp();
    const text = collectShadowText(el);
    expect(text).not.toContain('How do I use the AI chat feature?');
    expect(text).toContain('How do I ask the AI a question?');
  });
});

describe('HelpSurface — settle transients on hide (tempdoc 609)', () => {
  it('resets export feedback on disconnect but KEEPS the expanded FAQ choice', async () => {
    const el = await mountHelp();
    const v = el as unknown as {
      exportPath: string | null;
      exportError: string | null;
      expanded: number | null;
    };
    v.exportPath = 'F:/diag/export.zip';
    v.exportError = 'Export failed: disk full';
    v.expanded = 3; // the user's open FAQ item (recoverable)

    el.remove(); // navigate away (instance retained; settleTransients fires via JfElement)

    expect(v.exportPath).toBeNull(); // stale export feedback settled
    expect(v.exportError).toBeNull();
    expect(v.expanded).toBe(3); // the open FAQ choice is recoverable, kept
  });
});

describe('HelpSurface — redacted diagnostic summary copy', () => {
  beforeEach(() => {
    resetOperationCatalog();
    __clearAggregateRegistry();
    __resetBootstrap();
    __resetJournalForTest();
    bootstrapAggregateSubstrate();
  });

  afterEach(() => {
    document.querySelectorAll('jf-help-surface').forEach((el) => el.remove());
    localStorage.clear();
    resetOperationCatalog();
    __clearAggregateRegistry();
    __resetBootstrap();
    __resetJournalForTest();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('keeps hostile payload out of every frontend sink on the real operation path', async () => {
    const hostileSummary = 'PRIVATE-DIAGNOSTIC-SINK-SENTINEL-899';
    seedOperationCatalog(summaryCatalog());
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({
        success: true,
        message: 'backend message must not be rendered',
        structuredData: { summary: hostileSummary },
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchImpl);
    const log = vi.spyOn(console, 'log').mockImplementation(() => undefined);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    const el = await mountHelp();
    el.copyText = vi.fn().mockResolvedValue(true);
    const operation = summaryOperation(el);
    await (operation as unknown as { updateComplete: Promise<void> }).updateComplete;
    const opButton = operation.querySelector<HTMLElement>('jf-op-button');
    expect(opButton).not.toBeNull();
    await (opButton as unknown as { updateComplete: Promise<void> }).updateComplete;
    const actionButton = opButton?.shadowRoot?.querySelector('jf-action-button');
    expect(actionButton).not.toBeNull();

    actionButton?.dispatchEvent(new CustomEvent('action-invoke', {
      detail: { operationId: 'core.copy-diagnostic-summary', risk: 'LOW' },
      bubbles: true,
      composed: true,
    }));

    await vi.waitFor(() => expect(el.copyText).toHaveBeenCalledWith(hostileSummary));
    await el.updateComplete;

    expect(fetchImpl.mock.calls.some((call) => String(call[0]).includes('/invoke'))).toBe(true);
    expect(collectShadowText(el)).toContain('Diagnostic summary copied.');
    expect(collectShadowText(el)).not.toContain(hostileSummary);
    expect(JSON.stringify(listJournal())).not.toContain(hostileSummary);
    expect(exportJournalArchive()).not.toContain(hostileSummary);
    expect(storageText()).not.toContain(hostileSummary);
    expect(JSON.stringify(log.mock.calls)).not.toContain(hostileSummary);
    expect(JSON.stringify(warn.mock.calls)).not.toContain(hostileSummary);
    expect(JSON.stringify(error.mock.calls)).not.toContain(hostileSummary);
  });

  it('copies the structured summary without rendering or persisting its payload', async () => {
    const el = await mountHelp();
    const hostileSummary = 'PRIVATE-DIAGNOSTIC-SENTINEL-7d2e';
    el.copyText = vi.fn().mockResolvedValue(true);

    await dispatchSummarySuccess(el, hostileSummary);

    expect(el.copyText).toHaveBeenCalledOnce();
    expect(el.copyText).toHaveBeenCalledWith(hostileSummary);
    const text = collectShadowText(el);
    expect(text).toContain('Diagnostic summary copied.');
    expect(text).not.toContain(hostileSummary);
    expect(storageText()).not.toContain(hostileSummary);
  });

  it('shows only fixed failure copy when the clipboard rejects the write', async () => {
    const el = await mountHelp();
    const hostileSummary = 'PRIVATE-CLIPBOARD-FAILURE-SENTINEL';
    el.copyText = vi.fn().mockResolvedValue(false);

    await dispatchSummarySuccess(el, hostileSummary);

    const text = collectShadowText(el);
    expect(text).toContain('Could not copy diagnostic summary.');
    expect(text).not.toContain(hostileSummary);
    expect(text).not.toContain('backend message must not be rendered');
  });

  it('does not render backend error details', async () => {
    const el = await mountHelp();
    const hostileError = 'PRIVATE-BACKEND-ERROR-SENTINEL';

    summaryOperation(el).dispatchEvent(new CustomEvent('op-error', {
      bubbles: true,
      composed: true,
      detail: { message: hostileError },
    }));
    await el.updateComplete;

    const text = collectShadowText(el);
    expect(text).toContain('Could not copy diagnostic summary.');
    expect(text).not.toContain(hostileError);
  });
});
