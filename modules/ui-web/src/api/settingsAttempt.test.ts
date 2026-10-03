// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  captureSettingsPatch, createSettingsAttempt, executeSettingsAttempt,
  requireSettingsWitness, saveAbsoluteSettings, SettingsAttemptError,
  type SettingsAttempt, type SettingsTransport,
} from './settingsAttempt.js';
import { __resetUiModeForTest, enqueueUiModeSettings, UI_MODE_INTENT_HEADER } from '../shell-v0/state/uiModeState.js';

const zero = { acceptedRevision: 0, lastCommittedOperationKey: null };
const priorKey = '01993ba0-0000-7000-8000-000000000001';
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
function complete(attempt: SettingsAttempt, extra = {}): Response {
  return json({ state: 'COMPLETE', operationKey: attempt.operationKey,
    witness: { acceptedRevision: attempt.witness.acceptedRevision + 1, lastCommittedOperationKey: attempt.operationKey }, ...extra });
}
function completionFor(init?: RequestInit): Response {
  const body = JSON.parse(String(init?.body)) as SettingsAttempt;
  return complete(body);
}

beforeEach(() => { vi.useFakeTimers(); __resetUiModeForTest(); });
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

describe('frozen settings attempts', () => {
  it('uses canonical v7 timestamp/random bits and snapshots all caller-owned values', () => {
    vi.setSystemTime(0x010203040506);
    const patch = { ui: { excludePatterns: ['first'] } };
    const witness = { ...zero };
    const headers = new Headers({ [UI_MODE_INTENT_HEADER]: 'client:1' });
    const attempt = createSettingsAttempt(patch, witness, headers);
    patch.ui.excludePatterns.push('later');
    witness.acceptedRevision = 1;
    headers.set(UI_MODE_INTENT_HEADER, 'client:2');
    expect(attempt.operationKey).toMatch(/^01020304-0506-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(createSettingsAttempt({}, zero).operationKey).not.toBe(attempt.operationKey);
    expect(JSON.parse(attempt.body)).toEqual({ ui: { excludePatterns: ['first'] }, witness: zero, operationKey: attempt.operationKey });
    expect(new Headers(attempt.headers).get(UI_MODE_INTENT_HEADER)).toBe('client:1');
    expect(Object.isFrozen(attempt)).toBe(true);
    expect(Object.isFrozen(attempt.witness)).toBe(true);
    expect(Object.isFrozen(attempt.headers)).toBe(true);
  });

  it.each([
    null, undefined, { acceptedRevision: -1, lastCommittedOperationKey: null },
    { acceptedRevision: 0, lastCommittedOperationKey: priorKey },
    { acceptedRevision: 1, lastCommittedOperationKey: null },
    { acceptedRevision: 1, lastCommittedOperationKey: 'not-v7' },
    { acceptedRevision: Number.MAX_SAFE_INTEGER + 1, lastCommittedOperationKey: priorKey },
  ])('refuses invalid or missing witnesses before a request: %j', (witness) => {
    expect(() => requireSettingsWitness(witness)).toThrow(SettingsAttemptError);
  });

  it('replays identical body and mode header across network loss, admission and open rows', async () => {
    const attempt = createSettingsAttempt({ ui: { mode: 'advanced' } }, zero, { [UI_MODE_INTENT_HEADER]: 'client:8' });
    const send = vi.fn<SettingsTransport>()
      .mockRejectedValueOnce(new TypeError('connection lost'))
      .mockResolvedValueOnce(json({ errorCode: 'RECONFIGURE_IN_PROGRESS', retryable: true }, 409))
      .mockResolvedValueOnce(json({ state: 'ACCEPTED', operationKey: attempt.operationKey }, 202))
      .mockResolvedValueOnce(json({ state: 'RUNNING', operationKey: attempt.operationKey }, 202))
      .mockResolvedValueOnce(complete(attempt));
    let settled = false;
    const result = executeSettingsAttempt(send, attempt).then((value) => { settled = true; return value; });
    await vi.advanceTimersByTimeAsync(999);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect((await result).state).toBe('COMPLETE');
    expect(send).toHaveBeenCalledTimes(5);
    for (const [path, init] of send.mock.calls) {
      expect(path).toBe('/api/settings/v2');
      expect(init?.method).toBe('POST');
      expect(init?.body).toBe(attempt.body);
      expect(init?.headers).toEqual(attempt.headers);
    }
    expect(vi.getTimerCount()).toBe(0);
  });

  it('preserves original receipt-only A-to-B outcome after another writer without GET', async () => {
    const attempt = createSettingsAttempt({ ui: { theme: 'dark' } }, { acceptedRevision: 4, lastCommittedOperationKey: priorKey });
    const send = vi.fn<SettingsTransport>().mockResolvedValue(complete(attempt));
    const result = await executeSettingsAttempt(send, attempt);
    expect(result.witness.acceptedRevision).toBe(5);
    expect(result.projection.ui).toBeUndefined();
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0]?.[1]?.method).toBe('POST');
  });

  it('retries explicit capacity and keeps a gaps row unsettled without replacing its key', async () => {
    const attempt = createSettingsAttempt({}, zero);
    const send = vi.fn<SettingsTransport>()
      .mockResolvedValueOnce(json({ errorCode: 'OPERATIONS_CAPACITY', retryable: true }, 503))
      .mockResolvedValueOnce(json({ operationKey: attempt.operationKey, state: 'COMPLETE_WITH_GAPS' }, 202))
      .mockResolvedValueOnce(complete(attempt));
    let settled = false;
    const result = executeSettingsAttempt(send, attempt).then((value) => { settled = true; return value; });
    await vi.advanceTimersByTimeAsync(499);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect((await result).operationKey).toBe(attempt.operationKey);
    expect(send.mock.calls.map(([, init]) => init?.body)).toEqual([attempt.body, attempt.body, attempt.body]);
  });

  it('does not retry capacity without a retryable admission guarantee', async () => {
    const attempt = createSettingsAttempt({}, zero);
    const send = vi.fn<SettingsTransport>().mockResolvedValue(json({ errorCode: 'OPERATIONS_CAPACITY', retryable: false }, 503));
    await expect(executeSettingsAttempt(send, attempt)).rejects.toMatchObject({ code: 'OPERATIONS_CAPACITY', attempt });
    expect(send).toHaveBeenCalledTimes(1);
  });

  it.each(['VERSION_CONFLICT', 'OPERATION_KEY_REUSED', 'OPERATION_STORAGE_FAILED'])('retains %s and accepted identity without refreshing the attempt', async (errorCode) => {
    const attempt = createSettingsAttempt({ ui: { theme: 'dark' } }, zero);
    const body = { errorCode, retryable: false, error: 'refused', operationKey: attempt.operationKey, operationRecordId: 'row' };
    const send = vi.fn<SettingsTransport>().mockResolvedValue(json(body, 409));
    await expect(executeSettingsAttempt(send, attempt)).rejects.toMatchObject({ code: errorCode, attempt, response: body });
    expect(send).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each([
    { state: 'COMPLETE', operationKey: priorKey, witness: { acceptedRevision: 1, lastCommittedOperationKey: priorKey } },
    { state: 'FAILED', operationKey: 'same' },
    { state: 'COMPLETE', operationKey: 'same' },
    { state: 'COMPLETE', operationKey: 'same', witness: { acceptedRevision: 99, lastCommittedOperationKey: priorKey } },
  ])('does not accept unrelated, failed or incomplete success envelopes: %j', async (body) => {
    const attempt = createSettingsAttempt({}, zero);
    const send = vi.fn<SettingsTransport>().mockResolvedValue(json({ ...body, operationKey: body.operationKey === 'same' ? attempt.operationKey : body.operationKey }));
    await expect(executeSettingsAttempt(send, attempt)).rejects.toMatchObject({ attempt });
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('rejects a 202 that echoes the request witness as committed', async () => {
    const attempt = createSettingsAttempt({}, zero);
    const send = vi.fn<SettingsTransport>().mockResolvedValue(json({ operationKey: attempt.operationKey, state: 'RUNNING', witness: zero }, 202));
    await expect(executeSettingsAttempt(send, attempt)).rejects.toMatchObject({ code: 'SETTINGS_RECEIPT_INVALID', attempt });
  });

  it('times out a transport that ignores cancellation, retaining identity and clearing timers', async () => {
    const attempt = createSettingsAttempt({}, zero);
    const send = vi.fn<SettingsTransport>().mockImplementation(() => new Promise(() => {}));
    const result = executeSettingsAttempt(send, attempt, { timeoutMs: 500 });
    const rejected = expect(result).rejects.toMatchObject({ code: 'SETTINGS_COMPLETION_UNKNOWN', attempt });
    await vi.advanceTimersByTimeAsync(500);
    await rejected;
    expect(send.mock.calls[0]?.[1]?.signal?.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('aborts open replay without resubmitting or losing its original key', async () => {
    const attempt = createSettingsAttempt({}, zero);
    const controller = new AbortController();
    const send = vi.fn<SettingsTransport>().mockImplementation(async () => json({ operationKey: attempt.operationKey, state: 'RUNNING' }, 202));
    const result = executeSettingsAttempt(send, attempt, { signal: controller.signal });
    const rejected = expect(result).rejects.toMatchObject({ attempt });
    await vi.advanceTimersByTimeAsync(100);
    controller.abort();
    await rejected;
    await vi.advanceTimersByTimeAsync(1_000);
    expect(send).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('does not send or leak a rejection when cancellation predates execution', async () => {
    const attempt = createSettingsAttempt({}, zero);
    const controller = new AbortController();
    controller.abort();
    const send = vi.fn<SettingsTransport>();
    await expect(executeSettingsAttempt(send, attempt, { signal: controller.signal })).rejects.toMatchObject({ attempt });
    expect(send).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('absolute intents and shared mode ordering', () => {
  it('captures a patch before a delayed observation, then uses that observation witness', async () => {
    let observe!: (response: Response) => void;
    const send = vi.fn<SettingsTransport>()
      .mockImplementationOnce(() => new Promise((resolve) => { observe = resolve; }))
      .mockImplementationOnce(async (_, init) => completionFor(init));
    const patch = { ui: { excludePatterns: ['event-time'] } };
    const result = saveAbsoluteSettings(send, patch);
    patch.ui.excludePatterns.push('later');
    await vi.advanceTimersByTimeAsync(0);
    observe(json({ witness: { acceptedRevision: 8, lastCommittedOperationKey: priorKey } }));
    expect((await result).witness.acceptedRevision).toBe(9);
    expect(JSON.parse(String(send.mock.calls[1]?.[1]?.body)).ui.excludePatterns).toEqual(['event-time']);
  });

  it('never manufactures a zero witness or posts after failed observation', async () => {
    const send = vi.fn<SettingsTransport>().mockResolvedValue(json({ ui: {} }));
    await expect(saveAbsoluteSettings(send, { ui: { theme: 'dark' } })).rejects.toMatchObject({ code: 'SETTINGS_WITNESS_INVALID' });
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('retains the frozen attempt when the overall absolute deadline expires during POST', async () => {
    const send = vi.fn<SettingsTransport>().mockResolvedValueOnce(json({ witness: zero }))
      .mockImplementationOnce(() => new Promise(() => {}));
    const result = saveAbsoluteSettings(send, { ui: { theme: 'dark' } }, { timeoutMs: 500 });
    const rejected = expect(result).rejects.toMatchObject({ code: 'SETTINGS_COMPLETION_UNKNOWN', attempt: { witness: zero } });
    await vi.advanceTimersByTimeAsync(500);
    await rejected;
    expect(send).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('holds the mode queue through 202 and observes the next witness only after COMPLETE', async () => {
    let revision = 0;
    let lastKey: string | null = null;
    let firstPost = true;
    const send = vi.fn<SettingsTransport>().mockImplementation(async (_, init) => {
      if (init?.method !== 'POST') return json({ witness: { acceptedRevision: revision, lastCommittedOperationKey: lastKey } });
      const attempt = JSON.parse(String(init.body)) as SettingsAttempt;
      if (firstPost) { firstPost = false; return json({ state: 'RUNNING', operationKey: attempt.operationKey }, 202); }
      revision++;
      lastKey = attempt.operationKey;
      return complete(attempt);
    });
    const firstPatch = captureSettingsPatch({ ui: { mode: 'advanced' } });
    const first = enqueueUiModeSettings(send, firstPatch);
    const second = enqueueUiModeSettings(send, { ui: { mode: 'simple' } });
    await vi.advanceTimersByTimeAsync(249);
    expect(send.mock.calls.filter(([, init]) => init?.method !== 'POST')).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect((await first).witness.acceptedRevision).toBe(1);
    expect((await second).witness.acceptedRevision).toBe(2);
    const posts = send.mock.calls.filter(([, init]) => init?.method === 'POST');
    expect(posts[0]?.[1]?.body).toBe(posts[1]?.[1]?.body);
    expect(posts[2]?.[1]?.body).not.toBe(posts[1]?.[1]?.body);
    expect(JSON.parse(String(posts[2]?.[1]?.body)).witness.acceptedRevision).toBe(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('keeps settings identity on a queued timeout and allows the next mode intent to proceed', async () => {
    const send = vi.fn<SettingsTransport>()
      .mockResolvedValueOnce(json({ witness: zero }))
      .mockImplementationOnce(() => new Promise(() => {}))
      .mockResolvedValueOnce(json({ witness: zero }))
      .mockImplementationOnce(async (_, init) => completionFor(init));
    const first = enqueueUiModeSettings(send, { ui: { mode: 'advanced' } }, { timeoutMs: 500 });
    const observed = first.then(() => undefined, (error: unknown) => error);
    const second = enqueueUiModeSettings(send, { ui: { mode: 'simple' } });
    await vi.advanceTimersByTimeAsync(500);
    expect(await observed).toMatchObject({ code: 'SETTINGS_COMPLETION_UNKNOWN', attempt: { witness: zero } });
    expect((await second).state).toBe('COMPLETE');
    expect(send).toHaveBeenCalledTimes(4);
    const firstInit = send.mock.calls[1]?.[1];
    const secondInit = send.mock.calls[3]?.[1];
    expect(new Headers(firstInit?.headers).get(UI_MODE_INTENT_HEADER)).toMatch(/:1$/);
    expect(new Headers(secondInit?.headers).get(UI_MODE_INTENT_HEADER)).toMatch(/:2$/);
    expect(vi.getTimerCount()).toBe(0);
  });
});
