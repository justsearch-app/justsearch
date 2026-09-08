// SPDX-License-Identifier: Apache-2.0
/**
 * Host-owned application-update state.
 *
 * The Tauri shell is the sole authority for release trust and installation.
 * This module only projects its status into the always-mounted chrome and
 * Settings, and deliberately keeps update commands out of the plugin API.
 */
import { isTauriRuntime } from '../../utils/tauriRuntime.js';

export type AppUpdateState =
  | 'idle'
  | 'checking'
  | 'up_to_date'
  | 'available'
  | 'downloading'
  | 'preparing'
  | 'prepared'
  | 'head_stopped'
  | 'engine_unrecoverable'
  | 'install_launching'
  | 'install_launched'
  | 'reconciling'
  | 'committed'
  | 'cancelled'
  | 'repair_required'
  | 'error';

export interface AppUpdateStatus {
  readonly state: AppUpdateState | string;
  readonly currentVersion: string;
  readonly availableVersion?: string | null;
  readonly releaseSequence?: number | null;
  readonly intentPhase?: string | null;
  readonly attemptId?: string | null;
  readonly error?: string | null;
  readonly bytesDownloaded?: number | null;
  readonly bytesTotal?: number | null;
  readonly interruptibleWithLoss?: boolean | null;
  readonly blockers?: readonly string[] | null;
}

type Listener = (status: AppUpdateStatus | null) => void;

let snapshot: AppUpdateStatus | null = null;
const listeners = new Set<Listener>();

function publish(status: AppUpdateStatus | null): AppUpdateStatus | null {
  snapshot = status;
  for (const listener of listeners) listener(status);
  return status;
}

async function invoke<T>(command: string): Promise<T> {
  const { invoke: tauriInvoke } = await import('@tauri-apps/api/core');
  return tauriInvoke<T>(command);
}

export function getAppUpdateStatus(): AppUpdateStatus | null {
  return snapshot;
}

export function subscribeAppUpdate(listener: Listener): () => void {
  listeners.add(listener);
  listener(snapshot);
  return () => listeners.delete(listener);
}

export async function refreshAppUpdateStatus(): Promise<AppUpdateStatus | null> {
  try {
    return publish(await invoke<AppUpdateStatus>('app_update_status'));
  } catch {
    // A newer web bundle can be served by an older desktop shell. Treat a
    // missing command as an absent capability, not a user-visible failure.
    return publish(null);
  }
}

export async function checkForAppUpdate(): Promise<AppUpdateStatus> {
  const current = snapshot;
  publish({
    state: 'checking',
    currentVersion: current?.currentVersion ?? 'unknown',
    availableVersion: current?.availableVersion,
    releaseSequence: current?.releaseSequence,
  });
  try {
    const status = await invoke<AppUpdateStatus>('check_for_app_update');
    publish(status);
    return status;
  } catch (error) {
    await refreshAppUpdateStatus();
    throw error;
  }
}

export async function installAppUpdate(): Promise<void> {
  const current = snapshot;
  if (current) publish({ ...current, state: 'downloading' });
  try {
    await invoke<void>('install_app_update');
    await refreshAppUpdateStatus();
  } catch (error) {
    await refreshAppUpdateStatus();
    throw error;
  }
}

/**
 * Starts the one passive startup check owned by the shell chrome.
 * It never downloads or installs an update. The delay keeps normal app
 * startup and Head readiness on the critical path ahead of release I/O.
 */
export function startAppUpdateMonitor(delayMs = 30_000): () => void {
  if (!isTauriRuntime()) return () => undefined;
  let stopped = false;
  void refreshAppUpdateStatus();
  const timer = window.setTimeout(() => {
    if (!stopped) void checkForAppUpdate().catch(() => undefined);
  }, delayMs);
  return () => {
    stopped = true;
    window.clearTimeout(timer);
  };
}

/** Test isolation for the singleton projection. */
export function __resetForTest(): void {
  snapshot = null;
  listeners.clear();
}
