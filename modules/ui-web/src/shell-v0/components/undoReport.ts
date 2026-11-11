// SPDX-License-Identifier: Apache-2.0
/**
 * What a confirmed undo did, said truthfully. Reversals applied in this window are done when they
 * are dispatched; a reversal the Engine performs is only a request until the Engine answers
 * (consent, an expired execution, a failure). The first message counts requests as asked, and a
 * second one follows with the Engine's answers — never a success claimed from the dispatch alone.
 */
import { applyEffect } from '../substrates/actions/index.js';
import type { UndoRun } from '../substrates/effects/index.js';

function count(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

/** `done` words the reversals applied here, e.g. `n => "Undid n AI actions"`. */
export function reportUndoRun(run: UndoRun, done: (reversed: number) => string): void {
  const parts: string[] = [];
  if (run.reversed > 0) parts.push(done(run.reversed));
  if (run.requested.length > 0) {
    parts.push(`${parts.length ? 'asked' : 'Asked'} the engine to undo ${count(run.requested.length, 'action')}`);
  }
  applyEffect({
    kind: 'toast',
    message: `${parts.length ? parts.join('; ') : 'Nothing was undone'}${run.skipped.length
      ? `; ${run.skipped.length} could no longer be undone` : ''}`,
    severity: run.skipped.length ? 'warning' : 'info',
  });
  const answers = run.requested.flatMap((request) => (request.settled ? [request.settled] : []));
  if (answers.length === 0) return;
  void Promise.all(answers).then((outcomes) => {
    const confirmed = outcomes.filter((outcome) => outcome === 'confirmed').length;
    const notDone = outcomes.length - confirmed;
    applyEffect({
      kind: 'toast',
      message: notDone === 0 ? `The engine confirmed ${count(confirmed, 'undo')}`
        : confirmed === 0 ? `The engine did not undo ${count(notDone, 'action')}`
          : `The engine confirmed ${count(confirmed, 'undo')}; ${notDone} not undone`,
      severity: notDone ? 'warning' : 'info',
    });
  });
}
