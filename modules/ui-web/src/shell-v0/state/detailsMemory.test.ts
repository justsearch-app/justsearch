// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// DP1 "state remembered per conversation" and C11 "Any userState change preserves co-resident values".
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { DETAILS_MEMORY_CAP, getDetailsMemory, rememberDetails } from './detailsMemory.js';
import {
  __DOCUMENT_STORAGE_KEY,
  __resetInMemoryStateForTest,
  __resetUserStateForTest,
  getDocument,
  getPluginSetting,
  mutateDocument,
  setPluginSetting,
  type DetailsMemory,
} from './UserStateDocument.js';
import { getChatWidth, setChatWidth } from './chatWidthState.js';

const memory = (over: Partial<DetailsMemory> = {}): DetailsMemory => ({ open: true, tab: 'source:1', docPath: '/a.md', turnId: 't1', sourceIndex: 1, ...over });

beforeEach(() => { localStorage.clear(); __resetUserStateForTest(); });
afterEach(() => { localStorage.clear(); __resetUserStateForTest(); });

describe('details memory', () => {
  it('holds each conversation apart, and replaces rather than appends', () => {
    rememberDetails('a', memory({ tab: 'context' }));
    rememberDetails('b', memory({ tab: 'source:0' }));
    rememberDetails('a', memory({ tab: 'source:2' }));
    expect(getDetailsMemory('a')?.tab).toBe('source:2');
    expect(getDetailsMemory('b')?.tab).toBe('source:0');
    expect(getDetailsMemory('never')).toBeNull();
  });

  it('survives a reload', () => {
    rememberDetails('a', memory());
    __resetInMemoryStateForTest();
    expect(getDetailsMemory('a')).toEqual(memory());
  });

  it('forgets the conversations written longest ago, past the cap', () => {
    for (let i = 0; i <= DETAILS_MEMORY_CAP; i += 1) rememberDetails(`c${i}`, memory());
    expect(getDetailsMemory('c0')).toBeNull();
    expect(getDetailsMemory('c1')).not.toBeNull();
    expect(Object.keys(getDocument().detailsByConversation ?? {})).toHaveLength(DETAILS_MEMORY_CAP);
  });

  it('a conversation written again moves to the end of the queue', () => {
    for (let i = 0; i < DETAILS_MEMORY_CAP; i += 1) rememberDetails(`c${i}`, memory());
    rememberDetails('c0', memory({ tab: 'context' }));
    rememberDetails('fresh', memory());
    expect(getDetailsMemory('c0')?.tab).toBe('context');
    expect(getDetailsMemory('c1')).toBeNull();
  });

  it('a malformed stored entry is dropped alone', () => {
    rememberDetails('good', memory());
    const stored = JSON.parse(localStorage.getItem(__DOCUMENT_STORAGE_KEY)!) as Record<string, unknown>;
    stored['detailsByConversation'] = { ...(stored['detailsByConversation'] as object), bad: { open: 'yes', tab: 3 }, worse: null };
    localStorage.setItem(__DOCUMENT_STORAGE_KEY, JSON.stringify(stored));
    __resetInMemoryStateForTest();
    expect(getDetailsMemory('good')).toEqual(memory());
    expect(getDetailsMemory('bad')).toBeNull();
    expect(getDetailsMemory('worse')).toBeNull();
  });

  it('a stored slice of the wrong shape reads as no memory', () => {
    rememberDetails('good', memory());
    const stored = JSON.parse(localStorage.getItem(__DOCUMENT_STORAGE_KEY)!) as Record<string, unknown>;
    stored['detailsByConversation'] = ['not', 'a', 'map'];
    localStorage.setItem(__DOCUMENT_STORAGE_KEY, JSON.stringify(stored));
    __resetInMemoryStateForTest();
    expect(getDetailsMemory('good')).toBeNull();
  });
});

describe('C11 co-resident userState survives', () => {
  it('chat width, pins, keybindings and plugin settings stay through a details write and a reload', () => {
    setChatWidth('wide');
    setPluginSetting('vendor.a', 'color', 'teal');
    const pin = { id: 'p1', query: 'heating', pinnedAt: 1, runs: [] };
    const binding = { key: 'Ctrl+I', commandId: 'core.action.shell.toggle-inspector', source: 'user' as const };
    mutateDocument(doc => ({ ...doc, pinnedSearches: [pin], keybindingOverrides: [binding] }));

    rememberDetails('a', memory());
    __resetInMemoryStateForTest();

    expect(getChatWidth()).toBe('wide');
    expect(getPluginSetting('vendor.a', 'color')).toBe('teal');
    expect(getDocument().pinnedSearches).toEqual([pin]);
    expect(getDocument().keybindingOverrides).toEqual([binding]);
    expect(getDetailsMemory('a')).toEqual(memory());
  });

  it('a later write of another slice keeps the details memory', () => {
    rememberDetails('a', memory());
    setChatWidth('narrow');
    __resetInMemoryStateForTest();
    expect(getDetailsMemory('a')).toEqual(memory());
  });
});
