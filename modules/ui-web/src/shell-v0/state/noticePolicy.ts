// SPDX-License-Identifier: Apache-2.0
/**
 * DE5 — the one place that decides what leaves the app. Principle 5, "Quiet by default, never
 * silent about trouble": with the window focused the pill, its announcement and the panel are the
 * notice, wherever in the app the person is. A desktop notification is only for a live event while
 * the app is in the background, and the taskbar badge is passive, so the setting does not touch it.
 */
import type { FrameStatus, StatusFact } from './frameStatus.js';
import type { NotificationsChoice } from './notificationsState.js';

const LONG_WORK_MIN_DURATION_MS = 60_000;

type NoticeKind = 'agent-request' | 'work-finished' | 'work-failed';

/**
 * One live event. `id` is that event's own: every window reporting it gives the same id, and no other
 * event has it (the shell shows an id once). It finds the row when the notification is clicked: the
 * row's id, or for an end listed only by its words in Earlier, those words ({@link endEventId}).
 */
export interface DesktopNotice {
  readonly id: string;
  readonly kind: NoticeKind;
  readonly title: string;
  readonly body?: string;
  /** False for anything rediscovered: startup snapshots, reconnects, late discovery (C6). */
  readonly live: boolean;
  /** Observed active time for finished work; unknown durations stay inside the app. */
  readonly activeDurationMs?: number;
}

export interface NoticeConditions {
  readonly notifications: NotificationsChoice;
  /** The window is focused, visible and not minimised. */
  readonly focused: boolean;
}

/** How a notice leaves the app, or null when it stays inside. */
export function routeDesktopNotice(notice: Pick<DesktopNotice, 'live' | 'kind' | 'activeDurationMs'>, { notifications, focused }: NoticeConditions): { sound: boolean } | null {
  if (!notice.live || focused || notifications === 'off') return null;
  if (notice.kind === 'work-finished' && (notice.activeDurationMs === undefined
    || !Number.isFinite(notice.activeDurationMs) || notice.activeDurationMs < LONG_WORK_MIN_DURATION_MS)) return null;
  return { sound: notifications === 'sound' };
}

/** The panel's first line and the deadline (ref-26); never the row's detail, which holds argument values. */
export function agentRequestNotice(row: Pick<StatusFact, 'id' | 'title' | 'deadline'>, live: boolean): DesktopNotice {
  return { id: row.id, kind: 'agent-request', title: row.title, ...(row.deadline ? { body: row.deadline } : {}), live };
}

/**
 * The id of an end listed only by its words ("Search is up to date"), which repeat from one event to
 * the next. `time` must be the engine's own, the same in every window; a window's clock is not.
 */
export function endEventId(words: string, time: number | string): string {
  return `${words}@${time}`;
}

/** The words of an {@link endEventId}, to find the row a clicked notification is about. */
export function noticeRowWords(id: string): string {
  return id.split('@')[0]!;
}

/**
 * A completion edge; the pill shows it regardless of its active duration. `live` says its end was
 * observed: work that ended across a gap in observation is only rediscovered (C6).
 */
export function workFinishedNotice(title: string, live: boolean, id: string, activeDurationMs?: number): DesktopNotice {
  return { id, kind: 'work-finished', title, live, activeDurationMs };
}

/** A failure edge. The failure's own message stays in the panel: it can carry paths. */
export function workFailedNotice(title: string, live: boolean, id: string): DesktopNotice {
  return { id, kind: 'work-failed', title, body: 'Open JustSearch to see what to do next.', live };
}

/** The taskbar badge: the same count the pill shows for Needs you, so Earlier never adds to it. */
export function needsYouCount(model: Pick<FrameStatus, 'sections'>): number {
  return model.sections.needs.length;
}
