// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

let completed = false;

// The second case observes the lifetime boundary established by shared cleanup.
describe.sequential('detached import lifetime', () => {
  it('starts deferred module work without awaiting it', () => {
    void import('./__fixtures__/delayedImport.js').then(() => {
      completed = true;
    });
    expect(completed).toBe(false);
  });

  it('finishes pending imports before the next test', () => {
    expect(completed).toBe(true);
  });
});
