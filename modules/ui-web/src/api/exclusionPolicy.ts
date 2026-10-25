// SPDX-License-Identifier: Apache-2.0
import type { SettingsV2 } from './generated/schema-types/settings-v2.js';

export type ExclusionEntry = NonNullable<NonNullable<SettingsV2['ui']>['excludePatterns']>[number];
export type ExactPathExclusion = Exclude<ExclusionEntry, string>;

/** Quotes preserve name boundaries; escapes and a trailing-space count make invisible bytes visible. */
export function describeExactPath(path: string): string {
  const trailing = /\s+$/u.exec(path)?.[0].length ?? 0;
  return `${JSON.stringify(path)}${trailing ? ` (${trailing} trailing whitespace character${trailing === 1 ? '' : 's'})` : ''}`;
}
