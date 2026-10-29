// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { describeRoute } from './routeHeuristic.js';

describe('describeRoute', () => {
  it('maps search → Search', () => {
    expect(describeRoute('search')).toBe('Search');
  });
  it('maps ask → Ask', () => {
    expect(describeRoute('ask')).toBe('Ask');
  });
});
