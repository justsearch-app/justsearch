// SPDX-License-Identifier: Apache-2.0
import 'ses';
lockdown({
  errorTaming: 'unsafe',
  consoleTaming: 'unsafe',
  domainTaming: 'unsafe',
  overrideTaming: 'severe',
});

import { expect, it } from 'vitest';
import type { StatusResponse } from '../../api/generated/index.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import { formatDeadline } from './format.js';

it('L1 formats today as local hours and minutes under SES lockdown', () => {
  const deadline = new Date();
  deadline.setHours(13, 24, 0, 0);
  const formatted = formatDeadline(deadline);
  expect(formatted).not.toContain('GMT');
  expect(formatted).not.toContain(String(deadline.getFullYear()));
  expect(formatted).toBe(new Intl.DateTimeFormat(undefined, {
    hour: '2-digit', minute: '2-digit',
  }).format(deadline));
});

it('L1 adds the short weekday only for another local calendar day under SES lockdown', () => {
  const now = new Date();
  for (const deadline of [
    new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 13, 24),
    new Date(now.getFullYear(), now.getMonth() + 1, now.getDate(), 13, 24),
    new Date(now.getFullYear() + 1, now.getMonth(), now.getDate(), 13, 24),
  ]) {
    const formatted = formatDeadline(deadline);
    expect(formatted).not.toContain('GMT');
    expect(formatted).not.toContain(String(deadline.getFullYear()));
    expect(formatted).toBe(new Intl.DateTimeFormat(undefined, {
      weekday: 'short', hour: '2-digit', minute: '2-digit',
    }).format(deadline));
  }
});

it('L1 retry and pause activity use the local deadline formatter under SES lockdown', () => {
  const now = new Date();
  const deadline = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 13, 24);
  const time = new Intl.DateTimeFormat(undefined, {
    weekday: 'short', hour: '2-digit', minute: '2-digit',
  }).format(deadline);
  const status = {
    worker: { core: { indexState: 'WAITING', pendingJobs: 1, indexHealthy: true },
      failure: { nextRetryAtMs: deadline.getTime() } },
  } as StatusResponse;
  expect(selectIndexingProgress(status, true, 1, []).activity.detail)
    .toBe(`1 file waiting. Next retry at ${time}.`);
  status.operating = { indexing: { state: 'PAUSED', reasons: ['user_paused'],
    untilEpochMs: deadline.getTime(), waitingForResume: true } } as StatusResponse['operating'];
  expect(selectIndexingProgress(status, true, 1, []).activity.detail)
    .toBe(`Paused by you. Until ${time}. Work is waiting for Resume.`);
});
