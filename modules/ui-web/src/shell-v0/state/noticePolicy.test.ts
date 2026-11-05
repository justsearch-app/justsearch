// SPDX-License-Identifier: Apache-2.0
/** DE5 — the one routing decision: what leaves the app, and how. */
import { describe, expect, it } from 'vitest';
import { agentRequestNotice, needsYouCount, routeDesktopNotice, workFailedNotice, workFinishedNotice } from './noticePolicy.js';
import type { FrameStatus, StatusFact } from './frameStatus.js';

const row = { id: 'authorization:a', title: 'Claude Desktop requests Remove Folder', deadline: 'Denied automatically in 4 min' };
const events = [agentRequestNotice(row, true), workFinishedNotice('Search is up to date', undefined, 60_000), workFailedNotice('AI install failed')];
const sections = (needs: number, earlier = 0) => ({ sections: { needs: Array<StatusFact>(needs), problems: [], running: [], earlier: Array<StatusFact>(earlier) } }) as Pick<FrameStatus, 'sections'>;

describe('DE5 routeDesktopNotice', () => {
  it.each(events.map(notice => [notice.kind, notice] as const))('%s: a focused window keeps it in the app, in every setting', (_kind, notice) => {
    for (const notifications of ['off', 'on', 'sound'] as const) expect(routeDesktopNotice(notice, { notifications, focused: true })).toBeNull();
  });
  it.each(events.map(notice => [notice.kind, notice] as const))('%s: in the background Off is silent, On has no sound, On with sound has it', (_kind, notice) => {
    expect(routeDesktopNotice(notice, { notifications: 'off', focused: false })).toBeNull();
    expect(routeDesktopNotice(notice, { notifications: 'on', focused: false })).toEqual({ sound: false });
    expect(routeDesktopNotice(notice, { notifications: 'sound', focused: false })).toEqual({ sound: true });
  });
  it('C6 a rediscovered event stays silent in the background, in every setting', () => {
    const replayed = agentRequestNotice(row, false);
    for (const notifications of ['off', 'on', 'sound'] as const) expect(routeDesktopNotice(replayed, { notifications, focused: false })).toBeNull();
  });
});

describe('DE5 work duration', () => {
  it('59 seconds stays in the app; 60 seconds notifies', () => {
    expect(routeDesktopNotice(workFinishedNotice('Search is up to date', undefined, 59_000), { notifications: 'on', focused: false })).toBeNull();
    expect(routeDesktopNotice(workFinishedNotice('Search is up to date', undefined, 60_000), { notifications: 'on', focused: false })).toEqual({ sound: false });
  });
  it('an unknown duration stays in the app', () => {
    expect(routeDesktopNotice(workFinishedNotice('Search is up to date'), { notifications: 'on', focused: false })).toBeNull();
  });
  it('a failure notifies at any length, including an unknown duration', () => {
    for (const activeDurationMs of [0, 59_000, 60_000, undefined]) {
      expect(routeDesktopNotice({ ...workFailedNotice('AI install failed'), activeDurationMs }, { notifications: 'on', focused: false })).toEqual({ sound: false });
    }
  });
  it('C6 replay stays silent even after long work', () => {
    expect(routeDesktopNotice({ ...workFinishedNotice('Search is up to date', undefined, 60_000), live: false }, { notifications: 'sound', focused: false })).toBeNull();
  });
});

describe('DE5 words', () => {
  it('an agent request says who, what and the deadline, and is found by its row', () => {
    expect(agentRequestNotice({ ...row, detail: '/home/me/Downloads' } as typeof row, true)).toEqual({ id: 'authorization:a', kind: 'agent-request',
      title: 'Claude Desktop requests Remove Folder', body: 'Denied automatically in 4 min', live: true });
  });
  it('an agent request without a deadline has no body', () => {
    expect(agentRequestNotice({ id: 'authorization:b', title: 'An agent requests Remove Folder' }, true)).not.toHaveProperty('body');
  });
  it('finished and failed work is found by its row, or by its words when it has none', () => {
    expect(workFinishedNotice('Rebuild finished', 'operation:op')).toMatchObject({ id: 'operation:op', kind: 'work-finished' });
    expect(workFinishedNotice('Search is up to date', undefined, 60_000)).toMatchObject({ id: 'Search is up to date', live: true });
    expect(workFailedNotice('Rebuild failed', 'operation:op')).toMatchObject({ id: 'operation:op', kind: 'work-failed', body: 'Open JustSearch to see what to do next.' });
  });
});

describe('DE5 needsYouCount', () => {
  it('counts Needs you only, and reaches zero', () => {
    expect(needsYouCount(sections(2))).toBe(2);
    expect(needsYouCount(sections(0))).toBe(0);
  });
  it('C6 retained Earlier records never add to the badge', () => {
    expect(needsYouCount(sections(1, 40))).toBe(1);
  });
});
