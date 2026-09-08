/**
 * Conformance adapter: the production supervisor (the Tauri shell).
 *
 * Lands functional at lane F stage B item B10, driving the crate's `supervisor-conformance` binary
 * — the actuator half `cargo test --lib` cannot see, because the shell crate has no `tests/`
 * directory and `--lib` means `#[cfg(test)] mod tests` only. At item B7 there is no such binary and
 * no decision seam in Rust yet, so this reports itself UNAVAILABLE and `run.mjs` exits non-zero.
 * Printing `skipped` here would make the harness green against an implementation that does not
 * exist, which is the one outcome a conformance harness must never produce.
 */

export const name = 'tauri';

export async function available() {
  return {
    ok: false,
    reason:
      'the Tauri supervisor is item B10; at this commit there is no src/supervisor.rs and no'
      + ' [[bin]] supervisor-conformance target for this adapter to drive',
  };
}

export async function runCase() {
  return {
    problems: ['adapter not implemented until item B10'],
  };
}
