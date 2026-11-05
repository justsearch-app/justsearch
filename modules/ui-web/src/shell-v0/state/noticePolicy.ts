// SPDX-License-Identifier: Apache-2.0
/**
 * DE5 — the one place that decides what leaves the app. Principle 5, "Quiet by default, never
 * silent about trouble": with the window focused the pill, its announcement and the panel are the
 * notice, wherever in the app the person is. A desktop notification is only for a live event while
 * the app is in the background, and the taskbar badge is passive, so the setting does not touch it.
 */
import type { FrameStatus, StatusFact } from './frameStatus.js';
import type { NotificationsChoice } from './notificationsState.js';

type NoticeKind = 'agent-request' | 'work-finished' | 'work-failed';

/**
 * One live event. `id` finds its row when the notification is clicked: the row's id, or for an event
 * listed only by its words in Earlier ("Search is up to date"), those words.
 */
export interface DesktopNotice {
  readonly id: string;
  readonly kind: NoticeKind;
  readonly title: string;
  readonly body?: string;
  /** False for anything rediscovered: startup snapshots, reconnects, late discovery (C6). */
  readonly live: boolean;
}

export interface NoticeConditions {
  readonly notifications: NotificationsChoice;
  /** The window is focused, visible and not minimised. */
  readonly focused: boolean;
}

/** How a notice leaves the app, or null when it stays inside. */
export function routeDesktopNotice(notice: Pick<DesktopNotice, 'live'>, { notifications, focused }: NoticeConditions): { sound: boolean } | null {
  if (!notice.live || focused || notifications === 'off') return null;
  return { sound: notifications === 'sound' };
}

/** The panel's first line and the deadline (ref-26); never the row's detail, which holds argument values. */
export function agentRequestNotice(row: Pick<StatusFact, 'id' | 'title' | 'deadline'>, live: boolean): DesktopNotice {
  return { id: row.id, kind: 'agent-request', title: row.title, ...(row.deadline ? { body: row.deadline } : {}), live };
}

/** A completion edge the pill just showed as "just finished", which is live by construction. */
export function workFinishedNotice(title: string, id = title): DesktopNotice {
  return { id, kind: 'work-finished', title, live: true };
}

/** A failure edge. The failure's own message stays in the panel: it can carry paths. */
export function workFailedNotice(title: string, id = title): DesktopNotice {
  return { id, kind: 'work-failed', title, body: 'Open JustSearch to see what to do next.', live: true };
}

/** The taskbar badge: the same count the pill shows for Needs you, so Earlier never adds to it. */
export function needsYouCount(model: Pick<FrameStatus, 'sections'>): number {
  return model.sections.needs.length;
}
