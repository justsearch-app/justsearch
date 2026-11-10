// SPDX-License-Identifier: Apache-2.0
export {};
declare global {
  interface Window {
    __justsearchBootFailure?: ((kind: 'entry' | 'evaluation' | 'bootstrap' | 'no-engine-address' | 'stalled') => void) & { started: boolean };
  }
}
