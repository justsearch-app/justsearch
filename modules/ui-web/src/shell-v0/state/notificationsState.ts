// SPDX-License-Identifier: Apache-2.0
/**
 * DE5 — the one Notifications choice (Settings › General): Off, On, On with sound.
 *
 * Projection over UserStateDocument's `notifications` slice, cross-profile and frontend-local like
 * `chatWidth`. A document without the slice means On, so existing users keep their notifications.
 */
import { signal } from '@lit-labs/signals';
import { getDocument, mutateDocument, subscribeProjection } from './UserStateDocument.js';

export type NotificationsChoice = 'off' | 'on' | 'sound';

const DEFAULT_NOTIFICATIONS: NotificationsChoice = 'on';

const tick = signal(0);
subscribeProjection(
  (doc) => doc.notifications ?? DEFAULT_NOTIFICATIONS,
  () => tick.set(tick.get() + 1),
);

/** The current choice, read live from the document; reactive inside a SignalWatcher render. */
export function getNotifications(): NotificationsChoice {
  void tick.get();
  return getDocument().notifications ?? DEFAULT_NOTIFICATIONS;
}

/** Persist the choice; the next live event reads it, so nothing else needs telling. */
export function setNotifications(notifications: NotificationsChoice): void {
  mutateDocument((doc) => ({ ...doc, notifications }));
}
