// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { StatusResponse } from '../../api/generated/index.js';
import { selectIndexingPhase, selectIndexingProgress } from './indexingProgress.js';

function status(state: string, reason = 'user_paused', pendingJobs = 12, untilEpochMs: number | null = null): StatusResponse {
  return {
    operating: { indexing: { state, reasons: [reason], untilEpochMs, waitingForResume: pendingJobs > 0 } },
    worker: { core: { indexState: pendingJobs ? 'INDEXING' : 'IDLE', pendingJobs, indexHealthy: true },
      migration: { processingJobsCount: state === 'PAUSING' ? 1 : 0 },
      enrichment: { backfillMode: 'idle' } },
  } as unknown as StatusResponse;
}
const select = (s: StatusResponse, live = true) => selectIndexingProgress(s, live, 20, []);

describe('WP3.7 status-owned pause projection', () => {
  it('S37.2 follows PAUSING then PAUSED, withholding progress estimates throughout', () => {
    const finishing = select(status('PAUSING'));
    expect(finishing.phase).toBe('pausing');
    expect(finishing.activity.summary).toBe('Pausing — finishing current files');
    expect(selectIndexingPhase(status('PAUSING'))).toBe('pausing');
    const quiet = select(status('PAUSED'));
    expect(quiet.phase).toBe('paused');
    expect(selectIndexingPhase(status('PAUSED'))).toBe('paused');
    for (const p of [finishing, quiet]) {
      expect(p.indexingPercent).toBeNull();
      expect(p.etaSeconds).toBeNull();
      expect(p.enrichingEtaSeconds).toBeNull();
      expect(p.activity.detail).toContain('Paused by you');
    }
  });

  it('S37.3 names the manual deadline and recovery reason, with neutral wording for unknown reasons', () => {
    const deadline = Date.UTC(2035, 0, 2, 15, 30);
    const manual = select(status('PAUSED', 'user_paused', 12, deadline));
    expect(manual).toMatchObject({ pause: { reason: 'user_paused', untilEpochMs: deadline } });
    expect(manual.activity.detail).toContain(new Date(deadline).toLocaleString());
    expect(select(status('PAUSED', 'settings_recovery')).activity.detail).toContain('Settings recovery');
    for (const reason of ['future_reason', 'constructor']) {
      const unknown = select(status('PAUSED', reason));
      expect(unknown.phase).toBe('paused');
      expect(unknown.activity.detail).toContain('Indexing is on hold');
    }
  });

  it('S37.6 keeps an empty paused queue visible instead of Ready', () => {
    const p = select(status('PAUSED', 'user_paused', 0));
    expect(p.phase).toBe('paused');
    expect(p.activity.summary).toBe('Indexing paused');
    expect(p.activity.detail).toContain('Paused by you');
    expect(p.activity.summary).not.toContain('Ready');
  });

  it('S37.5 takes resume from status, never from a reason or stable counts alone', () => {
    expect(select(status('PAUSED')).phase).toBe('paused');
    expect(select(status('RUNNING')).phase).toBe('indexing');
    expect(select(status('RUNNING')).activity.summary).not.toContain('paused');
    expect(select(status('PAUSED'), false).activity.confirmed).toBe(false);
  });

  it('N37.1 automatic holds keep their work-specific explanations and active text indexing', () => {
    for (const [reason, wording] of [['game_mode', 'game is running'], ['gpu_busy_external', 'another app'], ['energy_saver', 'save energy']]) {
      const p = select(status('PAUSED', reason));
      expect(p.phase).toBe('indexing');
      expect(p.activity.summary).toBe('Text indexing continues');
      expect(p.activity.detail).toContain(wording);
    }
    const noPolicyReason = status('PAUSED');
    noPolicyReason.operating!.indexing!.reasons = [];
    expect(select(noPolicyReason).phase).toBe('indexing');
    expect(select(noPolicyReason).indexingPercent).toBe(40);
  });
});
