// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom
import { expect, it, vi } from 'vitest';
import './SecuritySurface.js';

interface RestoreHarness {
  host_: { data: { fetch: (path: string, init?: RequestInit) => Promise<Response> } };
  encImportSummary: string | null;
  encError: string | null;
  importBackup(file: File, passphrase: string): Promise<void>;
}

it('reports restored and skipped counts when saved backup entries have invalid ids', async () => {
  const el = document.createElement('jf-security-surface') as unknown as RestoreHarness;
  el.host_ = {
    data: {
      fetch: vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ restored: { conversations: 2 }, skipped: 5 }),
      }),
    },
  };
  const file = { text: async () => '{"format":"justsearch-backup/v1"}' } as File;
  await el.importBackup(file, 'passphrase');
  expect(el.encError).toBeNull();
  expect(el.encImportSummary).toContain('Restored 2 conversations.');
  expect(el.encImportSummary).toContain('Skipped 5 entries with invalid ids.');
});

it('does not say everything already exists when all backup entries have invalid ids', async () => {
  const el = document.createElement('jf-security-surface') as unknown as RestoreHarness;
  el.host_ = {
    data: {
      fetch: vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ restored: { conversations: 0 }, skipped: 1 }),
      }),
    },
  };
  const file = { text: async () => '{}' } as File;
  await el.importBackup(file, 'passphrase');
  expect(el.encImportSummary).toBe('Nothing new to restore. Skipped 1 entry with an invalid id.');
});
