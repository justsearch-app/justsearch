// SPDX-License-Identifier: Apache-2.0
/**
 * Whether a run list is what the backend last said. A read that fails keeps the rows it had and says
 * so: an empty list must never stand for "could not ask", and old rows never pass for fresh ones.
 */
import { formatRelative } from '../utils/relativeTime.js';

export interface RunListLoad {
  readonly failed: boolean;
  /** When a read last succeeded; null before the first one. */
  readonly loadedAt: number | null;
}

export const RUN_LIST_UNREAD: RunListLoad = { failed: false, loadedAt: null };

export const runListLoaded = (): RunListLoad => ({ failed: false, loadedAt: Date.now() });

export const runListFailed = (previous: RunListLoad): RunListLoad => ({ failed: true, loadedAt: previous.loadedAt });

/** The sentence for a failed read, or null when the list can be believed. */
export function runListNotice(load: RunListLoad, noun: string): string | null {
  if (!load.failed) return null;
  return load.loadedAt === null
    ? `Couldn't load ${noun}.`
    : `Couldn't refresh ${noun}. Showing the list last seen ${formatRelative(load.loadedAt)}.`;
}
