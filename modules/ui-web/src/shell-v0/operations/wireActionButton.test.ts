// @vitest-environment happy-dom
/**
 * Tests for wireActionButton (slice 3a-1-2 Phase 4).
 */
import { describe, it, expect, vi, beforeAll } from 'vitest';
import '../components/ActionButton';
import type { ActionButton } from '../components/ActionButton';
import { OperationClient, OperationError } from './OperationClient';
import { wireActionButton } from './wireActionButton';

beforeAll(() => {
  // happy-dom registers customElements at module-load (the import above).
});

function createButton(operationId: string, risk: 'LOW' | 'MEDIUM' | 'HIGH' = 'LOW'): ActionButton {
  const el = document.createElement('jf-action-button') as ActionButton;
  el.operationId = operationId;
  el.risk = risk;
  document.body.appendChild(el);
  return el;
}

function fakeOk(message = 'ok'): Response {
  return new Response(JSON.stringify({ success: true, message }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function fakeCapsule(token = 'capsule-token-xyz'): Response {
  return new Response(JSON.stringify({ capsule: token }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function fake428(pendingId = 'pa-1'): Response {
  return new Response(
    JSON.stringify({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId }),
    { status: 428, headers: { 'Content-Type': 'application/json' } },
  );
}

/**
 * Tempdoc 550 C3: HIGH-risk now does invoke(no token) → 428{pendingId} →
 * approve{pendingId} → re-invoke(capsule). Route by URL + whether the invoke body already
 * carries a confirmationToken (the re-invoke).
 */
function routedFetch(
  capsule = 'capsule-token-xyz',
  pendingId = 'pa-1',
): ReturnType<typeof vi.fn<typeof fetch>> {
  return vi.fn<typeof fetch>().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    if (url.includes('/authorizations/approve')) return Promise.resolve(fakeCapsule(capsule));
    const body = init?.body
      ? (JSON.parse(init.body as string) as { confirmationToken?: string })
      : {};
    return Promise.resolve(body.confirmationToken ? fakeOk() : fake428(pendingId));
  });
}

/** The first /invoke recorded (the gating call). */
function invokeCall(fetchImpl: ReturnType<typeof vi.fn<typeof fetch>>): RequestInit {
  const call = fetchImpl.mock.calls.find(c => String(c[0]).includes('/invoke'));
  if (!call) throw new Error('no /invoke call recorded');
  return call[1] as RequestInit;
}

/** The last /invoke recorded (the re-invoke carrying the approved capsule). */
function reinvokeCall(fetchImpl: ReturnType<typeof vi.fn<typeof fetch>>): RequestInit {
  const calls = fetchImpl.mock.calls.filter(c => String(c[0]).includes('/invoke'));
  const last = calls[calls.length - 1];
  if (!last) throw new Error('no /invoke call recorded');
  return last[1] as RequestInit;
}

describe('wireActionButton', () => {
  it('dispatches via OperationClient on action-invoke event', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(fakeOk('ping ok'));
    const client = new OperationClient({ apiBase: 'http://localhost', fetchImpl });
    const button = createButton('core.ping-backend', 'LOW');
    const onSuccess = vi.fn();

    const unwire = wireActionButton(button, client, { onSuccess });

    button.dispatchEvent(
      new CustomEvent('action-invoke', {
        detail: { operationId: 'core.ping-backend', risk: 'LOW' },
        bubbles: true,
        composed: true,
      }),
    );

    // Wait for microtasks to drain (the handler is async).
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(onSuccess).toHaveBeenCalledWith(expect.objectContaining({ message: 'ping ok' }));
    expect(button.pending).toBe(false);
    unwire();
  });

  it('passes args from argsBuilder', async () => {
    const fetchImpl = routedFetch();
    const client = new OperationClient({ apiBase: 'http://localhost', fetchImpl });
    const button = createButton('core.bulk-reindex', 'HIGH');

    const unwire = wireActionButton(button, client, {
      argsBuilder: detail => ({ corpusIds: ['default'], invokedBy: detail.risk }),
    });

    button.dispatchEvent(
      new CustomEvent('action-invoke', {
        detail: { operationId: 'core.bulk-reindex', risk: 'HIGH' },
        bubbles: true,
      }),
    );
    await new Promise(resolve => setTimeout(resolve, 0));

    const body = JSON.parse(invokeCall(fetchImpl).body as string);
    expect(body.args.corpusIds).toEqual(['default']);
    expect(body.args.invokedBy).toBe('HIGH');
    unwire();
  });

  it('recovers a HIGH-risk gate via approve-by-pendingId, then re-invokes with the capsule (tempdoc 550 C3)', async () => {
    const fetchImpl = routedFetch('capsule-abc', 'pa-77');
    const client = new OperationClient({ apiBase: 'http://localhost', fetchImpl });
    const button = createButton('core.apply-excludes', 'HIGH');

    const unwire = wireActionButton(button, client);

    button.dispatchEvent(
      new CustomEvent('action-invoke', {
        detail: { operationId: 'core.apply-excludes', risk: 'HIGH' },
        bubbles: true,
      }),
    );
    // invoke → 428 → approve → re-invoke is two awaited round-trips; drain microtasks.
    await new Promise(resolve => setTimeout(resolve, 0));
    await new Promise(resolve => setTimeout(resolve, 0));

    // The approve endpoint was hit BY pendingId (not by re-presenting op+args), and the
    // re-invoke carries the minted capsule.
    const approveCall = fetchImpl.mock.calls.find(c =>
      String(c[0]).includes('/authorizations/approve'),
    );
    expect(approveCall).toBeTruthy();
    const approveBody = JSON.parse((approveCall![1] as RequestInit).body as string);
    expect(approveBody.pendingId).toBe('pa-77');
    expect(approveBody.operationId).toBeUndefined(); // never an arbitrary op
    expect(JSON.parse(reinvokeCall(fetchImpl).body as string).confirmationToken).toBe('capsule-abc');
    unwire();
  });

  it('does NOT forward confirmationToken for LOW/MEDIUM risk', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(fakeOk());
    const client = new OperationClient({ apiBase: 'http://localhost', fetchImpl });
    const button = createButton('core.ping-backend', 'LOW');

    const unwire = wireActionButton(button, client);

    button.dispatchEvent(
      new CustomEvent('action-invoke', {
        detail: { operationId: 'core.ping-backend', risk: 'LOW' },
        bubbles: true,
      }),
    );
    await new Promise(resolve => setTimeout(resolve, 0));

    const init = fetchImpl.mock.calls[0]![1] as RequestInit;
    const body = JSON.parse(init.body as string);
    expect(body.confirmationToken).toBeUndefined();
    unwire();
  });

  it('invokes onError when the dispatch fails', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ success: false, errorClass: 'HANDLER_FAILURE', message: 'no' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    const client = new OperationClient({ apiBase: 'http://localhost', fetchImpl });
    const button = createButton('core.bulk-reindex', 'HIGH');
    const onError = vi.fn();

    const unwire = wireActionButton(button, client, { onError });

    button.dispatchEvent(
      new CustomEvent('action-invoke', {
        detail: { operationId: 'core.bulk-reindex', risk: 'HIGH' },
        bubbles: true,
      }),
    );
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0]![0]).toBeInstanceOf(OperationError);
    expect(button.pending).toBe(false);
    unwire();
  });

  it('clears button.pending after dispatch even on error', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new Error('network down'));
    const client = new OperationClient({ apiBase: 'http://localhost', fetchImpl });
    const button = createButton('core.ping-backend', 'LOW');

    const unwire = wireActionButton(button, client, { onError: () => {} });

    button.dispatchEvent(
      new CustomEvent('action-invoke', {
        detail: { operationId: 'core.ping-backend', risk: 'LOW' },
        bubbles: true,
      }),
    );
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(button.pending).toBe(false);
    unwire();
  });

  it('unsubscribe stops further dispatches', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(fakeOk());
    const client = new OperationClient({ apiBase: 'http://localhost', fetchImpl });
    const button = createButton('core.ping-backend', 'LOW');

    const unwire = wireActionButton(button, client);
    unwire();

    button.dispatchEvent(
      new CustomEvent('action-invoke', {
        detail: { operationId: 'core.ping-backend', risk: 'LOW' },
        bubbles: true,
      }),
    );
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(fetchImpl).not.toHaveBeenCalled();
  });
});
