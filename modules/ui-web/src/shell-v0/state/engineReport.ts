// SPDX-License-Identifier: Apache-2.0
/**
 * The engine-loss report (S5c SC5). Built without the engine, from a fixed list of fields, the same
 * rule as the start-up failure page in `index.html`: every value is a version string, a word from a
 * closed vocabulary, a time or a number. Nothing the engine, the host or an error wrote as free text
 * is copied, so no path, host, key or message can leave through it.
 */
import { getAiState } from './aiStateStore.js';
import { getEngineLoss, type EngineLoss } from './engineLoss.js';
import { isTauriRuntime } from '../../utils/tauriRuntime.js';

const SUPERVISOR_STATES = ['starting', 'running', 'stopping', 'restarting', 'exhausted', 'stopped'] as const;
/** Exit reasons the supervisor names from the exit-code register (governance/supervision-contract.v1.json). */
const EXIT_REASONS = ['requested_restart', 'ok', 'fatal_or_uncaught', 'data_dir_locked', 'out_of_memory',
  'escalated_restart', 'supervisor_forced', 'hang', 'quit', 'upgrade', 'restart'] as const;

export interface EngineReport {
  readonly productVersion: string;
  readonly runtimeKind: 'desktop' | 'browser';
  readonly operatingSystem: 'Windows' | 'macOS' | 'Linux' | 'Other';
  readonly time: string;
  readonly lastContactAgeSeconds: number | null;
  readonly supervisorState: (typeof SUPERVISOR_STATES)[number] | null;
  readonly reason: string;
  readonly recentExitCodes: Readonly<Record<string, number>>;
}

function age(ms: number | null, now: number): number | null {
  return ms === null ? null : Math.max(0, Math.round((now - ms) / 1000));
}

function reasonCode(loss: EngineLoss): string {
  const supervisor = loss.supervisor;
  const cause = loss.cause === 'not-ready' ? 'not_ready'
    : loss.cause === 'stopped' ? supervisor?.state === 'exhausted' ? 'restart_exhausted' : 'stopped'
      : loss.cause === 'unreachable' ? 'unreachable' : 'none';
  const exit = supervisor?.lastExit?.reason ?? '';
  const raw = supervisor?.reason ?? '';
  const detail = (EXIT_REASONS as readonly string[]).includes(exit) ? exit
    : /spawn_failed/.test(raw) ? 'spawn_failed' : /restart_failed/.test(raw) ? 'restart_failed' : '';
  return detail ? `${cause}:${detail}` : cause;
}

/** Exit codes and how often each occurred recently; integers only. */
function exitCodes(loss: EngineLoss): Record<string, number> {
  const codes: Record<string, number> = {};
  for (const [code, count] of Object.entries(loss.supervisor?.recentExits?.codes ?? {})) {
    if (/^-?\d{1,10}$/.test(code) && Number.isInteger(count) && count >= 0) codes[String(Number(code))] = count;
  }
  const last = loss.supervisor?.lastExit?.code;
  if (Number.isInteger(last) && codes[String(last)] === undefined) codes[String(last)] = 1;
  return codes;
}

export function buildEngineReport(now: number = Date.now()): EngineReport {
  const loss = getEngineLoss();
  const platform = typeof navigator === 'undefined' ? '' : navigator.platform;
  const version = typeof document === 'undefined' ? null
    : document.querySelector<HTMLMetaElement>('meta[name="justsearch-version"]')?.content ?? null;
  const supervisorState = loss.supervisor?.state ?? null;
  return {
    productVersion: version && /^[0-9A-Za-z.+-]{1,40}$/.test(version) ? version : 'unknown',
    runtimeKind: isTauriRuntime() ? 'desktop' : 'browser',
    operatingSystem: /Win/.test(platform) ? 'Windows' : /Mac/.test(platform) ? 'macOS' : /Linux/.test(platform) ? 'Linux' : 'Other',
    time: new Date(now).toISOString(),
    lastContactAgeSeconds: age(getAiState().connection.lastContactMs, now),
    supervisorState: supervisorState && (SUPERVISOR_STATES as readonly string[]).includes(supervisorState) ? supervisorState : null,
    reason: reasonCode(loss),
    recentExitCodes: exitCodes(loss),
  };
}

export function engineReportText(now: number = Date.now()): string {
  return JSON.stringify(buildEngineReport(now), null, 2);
}
