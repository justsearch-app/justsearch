// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * C11 — every window holds the whole document, and the browser shares one stored copy. Each window
 * here is its own load of the module over that shared storage, as two open windows are.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

const KEY = 'justsearch.userState.v2';
const stored = () => JSON.parse(localStorage.getItem(KEY)!) as Record<string, unknown> & {
  activeProfileId: string;
  profiles: Record<string, { activeThemeId: string | null; label: string; keybindingOverrides?: { key: string }[] }>;
};

async function openWindow() {
  vi.resetModules();
  const document = await import('./UserStateDocument.js');
  const notifications = await import('./notificationsState.js');
  const chatWidth = await import('./chatWidthState.js');
  const keybindings = await import('../commands/KeybindingRegistry.js');
  document.getDocument();
  return { document, notifications, chatWidth, keybindings };
}

beforeEach(() => {
  localStorage.clear();
});

describe('C11 a write from one window', () => {
  it('keeps Off chosen in another window', async () => {
    const a = await openWindow();
    const b = await openWindow();
    b.notifications.setNotifications('off');
    a.chatWidth.setChatWidth('wide');
    expect(stored()).toMatchObject({ notifications: 'off', chatWidth: 'wide' });
  });
  it('keeps the other window\'s value in every kind of field: cross-profile, per plugin and per profile', async () => {
    const a = await openWindow();
    const b = await openWindow();
    b.chatWidth.setChatWidth('narrow');
    b.document.setPluginSetting('plugin.b', 'kept', 1);
    b.document.mutateDocument(doc => ({ ...doc, activeThemeId: 'theme-from-b' }));
    a.document.setPluginSetting('plugin.a', 'added', 2);
    expect(stored()).toMatchObject({
      chatWidth: 'narrow',
      pluginSettings: { 'plugin.a': { added: 2 }, 'plugin.b': { kept: 1 } },
      profiles: { default: { activeThemeId: 'theme-from-b' } },
    });
  });
  it('keeps both windows\' entries when both add to the same list', async () => {
    const a = await openWindow();
    const b = await openWindow();
    b.document.recordRecentCommandId('from-b');
    a.document.recordRecentCommandId('from-a');
    expect(stored()['recentCommandIds']).toEqual(['from-a', 'from-b']);
  });
  it('keeps both windows\' keybindings when both add one', async () => {
    const a = await openWindow();
    const b = await openWindow();
    b.keybindings.registerKeybinding({ key: 'ctrl+b', commandId: 'from-b', source: 'user', provenance: CORE_PROVENANCE });
    a.keybindings.registerKeybinding({ key: 'ctrl+a', commandId: 'from-a', source: 'user', provenance: CORE_PROVENANCE });
    expect(stored().profiles['default']!.keybindingOverrides?.map(binding => binding.key).sort()).toEqual(['ctrl+a', 'ctrl+b']);
  });
  it('leaves the window it is made in up to date with what it kept', async () => {
    const a = await openWindow();
    const b = await openWindow();
    const seen: string[] = [];
    a.document.subscribeProjection(doc => doc.notifications ?? 'on', value => seen.push(value));
    b.notifications.setNotifications('off');
    a.chatWidth.setChatWidth('wide');
    expect(a.notifications.getNotifications()).toBe('off');
    expect(seen).toEqual(['on', 'off']);
  });
  it('that changes nothing still leaves the window up to date', async () => {
    const a = await openWindow();
    const b = await openWindow();
    b.notifications.setNotifications('off');
    a.document.mutateDocument(doc => doc);
    expect(a.notifications.getNotifications()).toBe('off');
  });
  it('keeps writing what it chose over the same field from earlier', async () => {
    const a = await openWindow();
    const b = await openWindow();
    b.notifications.setNotifications('off');
    a.notifications.setNotifications('sound');
    expect(stored()['notifications']).toBe('sound');
  });
  it('a later write from the first window keeps the value too', async () => {
    const a = await openWindow();
    const b = await openWindow();
    b.notifications.setNotifications('off');
    a.chatWidth.setChatWidth('wide');
    a.chatWidth.setChatWidth('narrow');
    expect(stored()).toMatchObject({ notifications: 'off', chatWidth: 'narrow' });
  });
});

describe('C11 profiles across windows', () => {
  async function twoProfiles() {
    const a = await openWindow();
    a.document.createProfile('work', 'Work');
    const b = await openWindow();
    return { a, b };
  }
  it('a window stays on its profile, and its edit lands there, while storage keeps the other window\'s choice', async () => {
    const { a, b } = await twoProfiles();
    b.document.setActiveProfileId('work');
    a.document.mutateDocument(doc => ({ ...doc, activeThemeId: 'theme-from-a' }));
    expect(a.document.getActiveProfileId()).toBe('default');
    expect(stored().activeProfileId).toBe('work');
    expect(stored().profiles['default']!.activeThemeId).toBe('theme-from-a');
    expect(stored().profiles['work']!.activeThemeId).toBeNull();
  });
  it('switching profile in the first window then takes over the stored choice', async () => {
    const { a, b } = await twoProfiles();
    a.document.createProfile('home', 'Home');
    b.document.setActiveProfileId('work');
    a.document.setActiveProfileId('home');
    a.chatWidth.setChatWidth('wide');
    expect(stored().activeProfileId).toBe('home');
    expect(Object.keys(stored().profiles).sort()).toEqual(['default', 'home', 'work']);
  });
  it('a profile created in another window survives a write here', async () => {
    const { a, b } = await twoProfiles();
    b.document.createProfile('home', 'Home');
    a.chatWidth.setChatWidth('wide');
    expect(Object.keys(stored().profiles)).toContain('home');
  });
  it('a window whose profile another window deleted moves to the stored one instead of bringing it back', async () => {
    const { a, b } = await twoProfiles();
    a.document.setActiveProfileId('work');
    b.document.deleteProfile('work');
    a.chatWidth.setChatWidth('wide');
    expect(a.document.getActiveProfileId()).toBe('default');
    expect(Object.keys(stored().profiles)).toEqual(['default']);
  });
  it('deleting the profile another window chose does not leave storage pointing at it', async () => {
    const { a, b } = await twoProfiles();
    b.document.setActiveProfileId('work');
    a.chatWidth.setChatWidth('wide');
    a.document.deleteProfile('work');
    expect(stored().activeProfileId).toBe('default');
  });
});

describe('C11 what the re-read leaves alone', () => {
  it('one window alone keeps a session-only edit whose save failed (F10)', async () => {
    const a = await openWindow();
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => { throw new Error('quota'); });
    a.notifications.setNotifications('off');
    setItem.mockRestore();
    a.chatWidth.setChatWidth('wide');
    expect(stored()).toMatchObject({ notifications: 'off', chatWidth: 'wide' });
  });
  it('a stored document from a newer build is not adopted (F9)', async () => {
    const a = await openWindow();
    a.notifications.setNotifications('off');
    localStorage.setItem(KEY, JSON.stringify({ version: 99, futureOnlyField: true }));
    const seen: string[] = [];
    a.document.subscribeProjection(doc => doc.notifications ?? 'on', value => seen.push(value));
    a.chatWidth.setChatWidth('wide');
    expect(a.notifications.getNotifications()).toBe('off');
    expect(seen).toEqual(['off']);
  });
  it('the stored format is unchanged: the same version and fields as before', async () => {
    const a = await openWindow();
    const b = await openWindow();
    b.notifications.setNotifications('off');
    a.chatWidth.setChatWidth('wide');
    expect(stored()['version']).toBe(2);
    expect(Object.keys(stored()).sort()).toEqual(['activeProfileId', 'chatWidth', 'notifications', 'profiles', 'version']);
  });
});
