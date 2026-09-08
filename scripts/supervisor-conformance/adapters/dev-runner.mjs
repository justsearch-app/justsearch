/**
 * Conformance adapter: the development supervisor (`scripts/dev/dev-runner.cjs`).
 *
 * Lands functional at lane F stage B item B8. At item B7 — the commit that fixes the contract —
 * there is no supervisor to drive yet, and this file says so by reporting itself UNAVAILABLE rather
 * than by passing zero cases. `run.mjs` turns that into a non-zero exit, which is the honest answer
 * to "does the dev-runner pass the contract?" on a branch where it does not implement it.
 */

export const name = 'dev-runner';

export async function available() {
  return {
    ok: false,
    reason:
      'the dev-runner supervisor is item B8; at this commit dev-runner.cjs still exits with its'
      + ' child rather than deciding about it, so there is nothing for the actuator half to drive',
  };
}

export async function runCase() {
  return {
    problems: ['adapter not implemented until item B8'],
  };
}
