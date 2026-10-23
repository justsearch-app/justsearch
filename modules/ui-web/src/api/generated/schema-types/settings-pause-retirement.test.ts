import { expect, it } from 'vitest';
import { settingsV2Schema } from './settings-v2';

it('S36.12 removes the retired AI-only pause preference from the canonical contract', () => {
  expect(settingsV2Schema.safeParse({ ui: { pauseIndexingDuringAi: true } }).success).toBe(false);
});
