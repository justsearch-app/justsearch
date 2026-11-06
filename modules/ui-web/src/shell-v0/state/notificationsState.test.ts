// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/** DE5/C11 — the Notifications choice: On by default, persisted, and co-resident values preserved. */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { getNotifications, setNotifications } from './notificationsState.js';
import { setChatWidth, getChatWidth } from './chatWidthState.js';
import { __DOCUMENT_STORAGE_KEY, __resetInMemoryStateForTest, __resetUserStateForTest } from './UserStateDocument.js';

const stored = () => JSON.parse(localStorage.getItem(__DOCUMENT_STORAGE_KEY)!) as Record<string, unknown>;
function reload(document: unknown): void {
  localStorage.setItem(__DOCUMENT_STORAGE_KEY, JSON.stringify(document));
  __resetInMemoryStateForTest();
}

beforeEach(() => __resetUserStateForTest());
afterEach(() => __resetUserStateForTest());

describe('DE5 the Notifications choice', () => {
  it('is On for a person who never chose, including an existing document without the field', () => {
    expect(getNotifications()).toBe('on');
    setChatWidth('wide');
    const { notifications: _absent, ...existing } = stored();
    reload(existing);
    expect(getNotifications()).toBe('on');
    expect(getChatWidth()).toBe('wide');
  });
  it.each(['off', 'on', 'sound'] as const)('%s survives a reload', (choice) => {
    setNotifications(choice);
    expect(stored()['notifications']).toBe(choice);
    reload(stored());
    expect(getNotifications()).toBe(choice);
  });
  it('a value outside the three choices reads as On and never reaches the document', () => {
    setNotifications('off');
    reload({ ...stored(), notifications: 'loud' });
    expect(getNotifications()).toBe('on');
  });
  it('C11 preserves co-resident values, in both directions', () => {
    setChatWidth('narrow');
    setNotifications('sound');
    expect(getChatWidth()).toBe('narrow');
    setChatWidth('wide');
    expect(getNotifications()).toBe('sound');
    reload(stored());
    expect([getChatWidth(), getNotifications()]).toEqual(['wide', 'sound']);
  });
});
