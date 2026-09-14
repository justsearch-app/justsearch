// SPDX-License-Identifier: Apache-2.0
import { settingsV2Schema, type SettingsV2 } from './generated/schema-types/settings-v2.js';

export type SettingsWitness = NonNullable<SettingsV2['witness']>;
export type SettingsPatch = Pick<SettingsV2, 'ui' | 'llm' | 'indexPaths'>;
export type SettingsTransport = (path: string, init?: RequestInit) => Promise<Response>;
const ENDPOINT = '/api/settings/v2';
const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const patchSchema = settingsV2Schema.pick({ ui: true, llm: true, indexPaths: true });

export interface SettingsAttempt {
  readonly operationKey: string;
  readonly witness: Readonly<SettingsWitness>;
  readonly body: string;
  readonly headers: Readonly<Record<string, string>>;
}

export interface SettingsAttemptOptions {
  signal?: AbortSignal;
  headers?: HeadersInit;
  timeoutMs?: number;
}

/** A COMPLETE receipt may omit settings; replay never substitutes a later GET. */
export interface SettingsCompletion {
  state: 'COMPLETE';
  operationKey: string;
  witness: SettingsWitness;
  projection: SettingsV2;
}

/** The original attempt remains available even when delivery or completion is uncertain. */
export class SettingsAttemptError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly attempt?: SettingsAttempt,
    readonly response?: Readonly<Record<string, unknown>>,
  ) {
    super(message);
    this.name = 'SettingsAttemptError';
  }
}

export function requireSettingsWitness(value: SettingsV2['witness']): SettingsWitness {
  if (!value || !Number.isSafeInteger(value.acceptedRevision) || value.acceptedRevision < 0
      || (value.acceptedRevision === 0 ? value.lastCommittedOperationKey !== null
        : typeof value.lastCommittedOperationKey !== 'string' || !UUID_V7.test(value.lastCommittedOperationKey))) {
    throw new SettingsAttemptError('Settings did not include a valid revision. Reload before saving.', 'SETTINGS_WITNESS_INVALID');
  }
  return { acceptedRevision: value.acceptedRevision, lastCommittedOperationKey: value.lastCommittedOperationKey };
}

/** Timestamp plus cryptographic randomness, matching the server's canonical UUIDv7 keys. */
function operationKey(): string {
  let timestamp = Date.now();
  if (!Number.isSafeInteger(timestamp) || timestamp < 0 || timestamp > 0xffffffffffff) {
    throw new Error('Cannot create a settings operation key with this clock');
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  for (let i = 5; i >= 0; i--) {
    bytes[i] = timestamp % 256;
    timestamp = Math.floor(timestamp / 256);
  }
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x70;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Capture at event time when a queued writer will run later. */
export function captureSettingsPatch(patch: SettingsPatch): SettingsPatch {
  return patchSchema.parse(patch);
}

/** Base-relative edits must pass the witness observed with the edited data. */
export function createSettingsAttempt(
  patch: SettingsPatch, witness: SettingsWitness, headers?: HeadersInit,
): SettingsAttempt {
  const capturedWitness = Object.freeze(requireSettingsWitness(witness));
  const key = operationKey();
  const capturedHeaders = new Headers(headers);
  capturedHeaders.set('Content-Type', 'application/json');
  return Object.freeze({
    operationKey: key,
    witness: capturedWitness,
    body: JSON.stringify({ ...captureSettingsPatch(patch), witness: capturedWitness, operationKey: key }),
    headers: Object.freeze(Object.fromEntries(capturedHeaders.entries())),
  });
}

/** One bounded, validated observation; a late transport result cannot publish a new base. */
export function readSettingsObservation(
  send: SettingsTransport, options: SettingsAttemptOptions = {},
): Promise<SettingsV2 & { witness: SettingsWitness }> {
  return withinDeadline(options, async (signal) => {
    const response = await send(ENDPOINT, { signal });
    if (!response.ok) throw refusal(await readObject(response), undefined, response.status);
    const settings = settingsV2Schema.parse(await response.json());
    signal.throwIfAborted();
    return { ...settings, witness: requireSettingsWitness(settings.witness) };
  });
}

/** Fresh observation is permitted only for independent absolute field intents. */
export async function saveAbsoluteSettings(
  send: SettingsTransport, patch: SettingsPatch, options: SettingsAttemptOptions = {},
): Promise<SettingsCompletion> {
  // Capture before awaiting GET: a later UI edit must not change this intent.
  const captured = captureSettingsPatch(patch);
  const headers = new Headers(options.headers);
  let attempt: SettingsAttempt | undefined;
  try {
    return await withinDeadline(options, async (signal) => {
      const settings = await readSettingsObservation(send, { ...options, signal });
      signal.throwIfAborted();
      attempt = createSettingsAttempt(captured, requireSettingsWitness(settings.witness), headers);
      return executeSettingsAttempt(send, attempt, { ...options, signal });
    });
  } catch (error) {
    if (!attempt || (error instanceof SettingsAttemptError && error.attempt)) throw error;
    throw new SettingsAttemptError(error instanceof Error ? error.message : 'Settings completion is unknown',
      'SETTINGS_COMPLETION_UNKNOWN', attempt);
  }
}

/** Retry exact serialized bytes until COMPLETE, terminal refusal, cancellation or bounded timeout. */
export async function executeSettingsAttempt(
  send: SettingsTransport, attempt: SettingsAttempt, options: SettingsAttemptOptions = {},
): Promise<SettingsCompletion> {
  try {
    return await withinDeadline(options, async (signal) => {
      for (;;) {
        signal.throwIfAborted();
        let response: Response;
        try {
          response = await send(ENDPOINT, {
            method: 'POST', headers: { ...attempt.headers }, body: attempt.body, signal,
          });
        } catch (error) {
          signal.throwIfAborted();
          // Fetch network failure leaves acceptance unknown; the same key is safe to replay.
          if (!(error instanceof TypeError)) throw error;
          await waitForRetry(signal);
          continue;
        }
        const body = await readObject(response);
        if (!response.ok) {
          const admission = (response.status === 409 && body['errorCode'] === 'RECONFIGURE_IN_PROGRESS')
            || (response.status === 503 && body['errorCode'] === 'OPERATIONS_CAPACITY');
          if (admission && body['retryable'] === true) {
            await waitForRetry(signal);
            continue;
          }
          throw refusal(body, attempt, response.status);
        }
        const projection = settingsV2Schema.parse(body);
        if (projection.operationKey !== attempt.operationKey) throw protocolFailure(attempt);
        if (response.status === 202) {
          if (!['ACCEPTED', 'RUNNING', 'COMPLETE_WITH_GAPS'].includes(projection.state ?? '') || projection.witness != null) {
            throw protocolFailure(attempt);
          }
          await waitForRetry(signal);
          continue;
        }
        const witness = requireSettingsWitness(projection.witness);
        if (projection.state !== 'COMPLETE' || witness.lastCommittedOperationKey !== attempt.operationKey
            || witness.acceptedRevision !== attempt.witness.acceptedRevision + 1) throw protocolFailure(attempt);
        return { state: 'COMPLETE', operationKey: attempt.operationKey, witness, projection };
      }
    });
  } catch (error) {
    if (error instanceof SettingsAttemptError && error.attempt) throw error;
    throw new SettingsAttemptError(
      error instanceof Error ? error.message : 'Settings completion is unknown',
      error instanceof SettingsAttemptError ? error.code : 'SETTINGS_COMPLETION_UNKNOWN', attempt,
    );
  }
}

function protocolFailure(attempt: SettingsAttempt): SettingsAttemptError {
  return new SettingsAttemptError('Settings response did not identify this completed attempt.', 'SETTINGS_RECEIPT_INVALID', attempt);
}

async function readObject(response: Response): Promise<Record<string, unknown>> {
  const value: unknown = await response.json();
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid settings response');
  return value as Record<string, unknown>;
}

function refusal(body: Record<string, unknown>, attempt: SettingsAttempt | undefined, status: number): SettingsAttemptError {
  const code = typeof body['errorCode'] === 'string' ? body['errorCode'] : `HTTP_${status}`;
  const message = code === 'VERSION_CONFLICT'
    ? 'Settings changed elsewhere. Reload and review your edit before saving again.'
    : typeof body['error'] === 'string' ? body['error'] : `Settings request failed (${code})`;
  return new SettingsAttemptError(message, code, attempt, body);
}

async function withinDeadline<T>(options: SettingsAttemptOptions, action: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const timeout = options.timeoutMs ?? 10_000;
  if (!Number.isFinite(timeout) || timeout <= 0) throw new Error('Invalid settings timeout');
  const controller = new AbortController();
  const abort = () => controller.abort(options.signal?.reason);
  options.signal?.addEventListener('abort', abort, { once: true });
  if (options.signal?.aborted) abort();
  const timer = setTimeout(() => controller.abort(new Error('Settings request timed out.')), timeout);
  let rejectAbort: () => void = () => {};
  const cancelled = new Promise<never>((_, reject) => {
    rejectAbort = () => reject(controller.signal.reason);
    controller.signal.addEventListener('abort', rejectAbort, { once: true });
    if (controller.signal.aborted) rejectAbort();
  });
  try {
    const work = Promise.resolve().then(() => {
      controller.signal.throwIfAborted();
      return action(controller.signal);
    });
    return await Promise.race([work, cancelled]);
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener('abort', abort);
    controller.signal.removeEventListener('abort', rejectAbort);
  }
}

function waitForRetry(signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer);
      signal.removeEventListener('abort', abort);
      reject(signal.reason);
    };
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, 250);
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) abort();
  });
}
